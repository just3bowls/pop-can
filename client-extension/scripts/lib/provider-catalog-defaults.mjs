export const DEFAULT_FINGERPRINT_VALUES = {
  tag: '',
  id: '',
  classes: [],
  role: '',
  ariaLabel: '',
  placeholder: '',
  name: '',
  dataTestId: '',
  text: '',
  editable: false,
  clickable: false,
  parentTag: '',
  parentClasses: [],
  parentText: '',
  siblingTexts: [],
  ancestorClasses: [],
  ancestorTexts: [],
  nearestText: '',
  nearInput: false,
  capturedAt: 0,
  x: 0,
  y: 0,
  width: 0,
  height: 0,
  scrollX: 0,
  scrollY: 0,
  innerWidth: 0,
  innerHeight: 0,
  docX: 0,
  docY: 0,
  anchorDeltaX: 0,
  anchorDeltaY: 0
}

export const isDefaultFingerprintValue = (key, value) => {
  if (!Object.prototype.hasOwnProperty.call(DEFAULT_FINGERPRINT_VALUES, key)) return false
  return JSON.stringify(value) === JSON.stringify(DEFAULT_FINGERPRINT_VALUES[key])
}
