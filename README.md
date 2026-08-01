# Magnetic Field Detector

Native Android app that reads the phone magnetometer (`TYPE_MAGNETIC_FIELD`) and displays:

- Total magnetic-field strength in microteslas (µT)
- X, Y and Z sensor axes
- Live signal graph
- Normal, elevated and strong-field indicators
- Sensor accuracy and calibration guidance
- Clear message when the phone has no magnetometer

## Build the APK

Every push to `main` runs **Build Android APK** in GitHub Actions.

Open **Actions**, select the latest successful run, then download the artifact named:

`Magnetic-Field-Detector-APK`

Extract the ZIP and install `app-debug.apk` on an Android phone. Android may ask you to allow installation from your browser or file manager.

## Important

This app detects changes in magnetic field. It is not a certified metal detector, safety instrument, medical device, or electrical diagnostic tool.
