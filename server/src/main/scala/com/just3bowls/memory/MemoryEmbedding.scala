package com.just3bowls.memory

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.{OnnxTensor, OrtEnvironment, OrtSession}

import java.nio.{FloatBuffer, LongBuffer}
import java.nio.file.{Files, Path}
import java.util
import scala.jdk.CollectionConverters._
import scala.util.hashing.MurmurHash3

trait EmbeddingProvider extends AutoCloseable {
  def identity: String
  def dimension: Int
  def available: Boolean
  def reason: String
  def embed(text: String): Array[Float]
  override def close(): Unit = ()
}

/** Dependency-free fallback used when the packaged model is absent. */
final class HashingEmbeddingProvider extends EmbeddingProvider {
  override val identity: String   = "local-hash-384-v1"
  override val dimension: Int     = 384
  override val available: Boolean = true
  override val reason: String     = "ONNX model unavailable; using local hash embeddings"

  override def embed(text: String): Array[Float] = {
    val normalized = MemoryPolicy.normalizeText(text)
    val vector     = Array.fill[Float](dimension)(0.0f)
    val chars      = normalized.filterNot(_.isWhitespace)
    val tokens     =
      normalized.split("""[^\p{L}\p{N}]+""").filter(_.nonEmpty).toList ++
        chars.sliding(2).filter(_.length == 2) ++
        chars.sliding(3).filter(_.length == 3)
    tokens.foreach { token =>
      val hash = MurmurHash3.stringHash(token)
      val slot = Math.floorMod(hash, vector.length)
      vector(slot) += (if ((hash & 1) == 0) 1.0f else -1.0f)
    }
    normalize(vector)
  }

  private def normalize(vector: Array[Float]): Array[Float] = {
    val norm = math.sqrt(vector.iterator.map(value => value.toDouble * value).sum)
    if (norm > 0.0) {
      var index = 0
      while (index < vector.length) {
        vector(index) = (vector(index) / norm).toFloat
        index += 1
      }
    }
    vector
  }
}

/**
 * Quantized multilingual-e5-small. The model and tokenizer are packaged beside the native host; initialization is
 * explicit so a missing model degrades to BM25 instead of failing Memory startup.
 */
final class OnnxE5EmbeddingProvider private[memory] (
    modelPath: Path,
    tokenizer: HuggingFaceTokenizer,
    environment: OrtEnvironment,
    session: OrtSession
) extends EmbeddingProvider {
  override val identity: String   = s"multilingual-e5-small-q8-v1:${modelPath.getFileName}"
  override val dimension: Int     = 384
  override val available: Boolean = true
  override val reason: String     = ""

  private val inputNames = session.getInputNames.asScala

  override def embed(text: String): Array[Float] = {
    val encoding = tokenizer.encode(Option(text).getOrElse("").trim)
    val ids      = encoding.getIds
    val mask     = encoding.getAttentionMask
    val shape    = Array(1L, ids.length.toLong)
    val tensors  = scala.collection.mutable.Map.empty[String, OnnxTensor]
    try {
      tensors.update(
        "input_ids",
        OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape)
      )
      if (inputNames.contains("attention_mask"))
        tensors.update(
          "attention_mask",
          OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape)
        )
      if (inputNames.contains("token_type_ids"))
        tensors.update(
          "token_type_ids",
          OnnxTensor.createTensor(environment, LongBuffer.wrap(Array.fill[Long](ids.length)(0L)), shape)
        )

      val input  = new util.HashMap[String, OnnxTensor]()
      tensors.foreach { case (name, tensor) => input.put(name, tensor) }
      val result = session.run(input)
      try {
        val output = result.get(0).asInstanceOf[OnnxTensor]
        val buffer = output.getFloatBuffer
        if (output.getInfo.getShape.length == 2) directVector(buffer, dimension)
        else meanPool(buffer, mask, dimension)
      } finally result.close()
    } finally tensors.values.foreach(_.close())
  }

  private def meanPool(buffer: FloatBuffer, mask: Array[Long], dimension: Int): Array[Float] = {
    val values = Array.fill[Float](dimension)(0.0f)
    val count  = math.max(1, mask.count(_ != 0L))
    var token  = 0
    while (token < mask.length) {
      if (mask(token) != 0L) {
        var column = 0
        while (column < dimension) {
          values(column) += buffer.get(token * dimension + column)
          column += 1
        }
      }
      token += 1
    }
    var index  = 0
    while (index < values.length) {
      values(index) = values(index) / count
      index += 1
    }
    normalize(values)
  }

  private def directVector(buffer: FloatBuffer, dimension: Int): Array[Float] = {
    val values = Array.fill[Float](dimension)(0.0f)
    var index  = 0
    while (index < dimension) {
      values(index) = buffer.get(index)
      index += 1
    }
    normalize(values)
  }

  private def normalize(values: Array[Float]): Array[Float] = {
    val norm = math.sqrt(values.iterator.map(value => value.toDouble * value).sum)
    if (norm > 0.0) {
      var index = 0
      while (index < values.length) {
        values(index) = (values(index) / norm).toFloat
        index += 1
      }
    }
    values
  }

  override def close(): Unit = {
    try session.close()
    catch { case _: Throwable => () }
    try tokenizer.close()
    catch { case _: Throwable => () }
  }
}

object EmbeddingProvider {
  private val ModelDirProperty = "pop-can.memory.model.dir"
  private val ModelDirEnv      = "POP_CAN_MEMORY_MODEL"

  def resolve(dataDir: Path): EmbeddingProvider =
    candidateModelDirs(dataDir)
      .find(isComplete)
      .flatMap(loadOnnx)
      .getOrElse(new HashingEmbeddingProvider)

  private def candidateModelDirs(dataDir: Path): List[Path] = {
    val explicit = Vector(
      Option(System.getProperty(ModelDirProperty)),
      sys.env.get(ModelDirEnv)
    ).flatten.map(Path.of(_).toAbsolutePath.normalize()).toList
    val codeDir  = Option(getClass.getProtectionDomain.getCodeSource)
      .flatMap(source => Option(source.getLocation))
      .map(url => Path.of(url.toURI))
      .flatMap { location =>
        val base = if (Files.isDirectory(location)) location else location.getParent
        Option(base).map(_.resolve("models").resolve("multilingual-e5-small"))
      }
      .toList
    explicit ++ codeDir ++ List(dataDir.resolve("models").resolve("multilingual-e5-small"))
  }

  private def isComplete(path: Path): Boolean =
    Files.isRegularFile(path.resolve("model.onnx")) && Files.isRegularFile(path.resolve("tokenizer.json"))

  private def loadOnnx(path: Path): Option[EmbeddingProvider] =
    try {
      val tokenizer   = HuggingFaceTokenizer.newInstance(path)
      val environment = OrtEnvironment.getEnvironment()
      val options     = new OrtSession.SessionOptions()
      val session     = environment.createSession(path.resolve("model.onnx").toString, options)
      Some(new OnnxE5EmbeddingProvider(path.resolve("model.onnx"), tokenizer, environment, session))
    } catch {
      case _: Throwable => None
    }
}
