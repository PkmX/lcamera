# L Camera

![Screenshot](screenshot.jpg?raw=true)

**L Camera** is an open-source experimental camera app for Android L devices using the new `android.hardware.camera2` API. Currently, the only supported device is Nexus 5 running Android Lollipop preview (LPX13D).

*Please note that this app is intended to test and study new features of the camera API, it is not for general uses as it lacks many basic camera features (location tagging, white balance, photo review, flash control, etc).*

[See what you can achieve on the Nexus 5 with the new API](http://imgur.com/a/qQkkR#0).

## Features

* True manual focus (adjustable focus distance)
* Manual exposure time (0.8", 1/2, 1/4, 1/6, 1/8, 1/15, 1/30, 1/60, 1/100, 1/125, 1/250, 1/500, 1/1000, 1/2000, 1/4000, 1/8000, 1/16000)
* Manual ISO (100, 200, 400, 800, 1600, 3200, 6400, 10000)
* DNG output support
* 30-fps full-resolution burst capture with focus stacking & exposure bracketing in DNG
* 30-fps full-resolution video recording
* Material design

## Installation

You can either install the pre-built debug APK ([`lcamera-debug.apk`](lcamera-debug.apk?raw=true)) found in the repository, or build and install the APK by yourself.

## Usage

Just use it like any camera! Tap the floating button on the left-bottom corner to bring up settings:

* Focus
 * Auto Focus: Whether the auto focus mechanism is enabled (tap on the preview to focus on a specific point).
 * Focus Distance: Manually control focus distance if auto focus is turned off.
* Exposure
 * Auto Exposure: Whether auto exposure and auto white balance routines are enabled (tap on the preview to start a metering sequence).
 * Shutter Speed: Control the exposure time. (Setting a slow shutter speed will affect preview frame rate)
 * ISO: Control the sensitivity of the sensor.
* Burst
 * Burst: Control whether burst capturing is enabled. If enabled, the camera will capture 7 DNG images at maximum resolution at 30 fps. (Note that JPEG output is disabled during burst capturing.)
 * Focus Stacking: If enabled, the camera will capture a series of images ranging from infinity focus and to the nearest focus distance possible.
 * Exposure Bracketing: If enabled, the camera will capture a series of 7 images ranging from -3 to +3 EV of the standard expousre. (Only the shutter speed is varied, the ISO stays the same)
* Photo/Video: Switch between photo capturing and video recording mode. The video is recorded at maximum resolution (3264x2448) at 30 fps (may be lower if you choose a slow shutter speed), encoded with H.264 at 35000kbps and AAC-LC in MP4 container. Focus distance and exposure are adjustable during recording.

If you are looking for a stopwatch to test the burst capture feature, check out [this jsfiddle](http://jsfiddle.net/jw2z5eeu/).

After capturing, output files will be saved to the `/sdcard/DCIM/Camera/` directory. Note that each DNG image is 15.36 MiB in size, so make sure you have plenty of free space available!

### Working with DNG files

Most RAW post-processing programs should be able to open them. While Adobe Lightroom is probably the most popular RAW editor, both [darktable](http://www.darktable.org/) (Linux, Mac) and [RawTherapee](http://rawtherapee.com/) (Windows, Linux, Mac) are both free alternatives that also offer very powerful editing capabilities.

## FAQ

### 'Cannot parse package' error while installing.
Make sure the downloaded apk is not corrupted, as it seems some browsers download GitHub's webpage instead of the actual apk.

### Will there be 60 fps (120 fps) recording?
It's being investigated. However it seems the camera isn't able to operate faster than 30 fps right now, even when requesting low resolution uncompressed YUV frames.

### Will you implement feature X?
Open an issue (one per feature please) on the tracker and I will see what I can do. However, the priority of development is investigating new ways to use the camera hardware rather than reimplement features that have been possible, or things that can be done in post-processing.

## Hacking/Technical

The app is written in the [Scala](http://www.scala-lang.org/) programming language and uses the following libraries/tools:

* [SBT](http://www.scala-sbt.org/): build tool.
* [Android SDK Plugin for SBT](https://github.com/pfn/android-sdk-plugin): for building Android apps with SBT.
* [Scaloid](https://github.com/pocorall/scaloid/): for UI layout and various helpers.
* [Scala.Rx](https://github.com/lihaoyi/scala.rx): for reactive value propagation.
* [Floating Action Button](https://github.com/makovkastar/FloatingActionButton): for the floating action button (FAB) featured in material design.

### How to build

You must have both **scala 2.11.2** and **sbt >= 0.13** installed.

To build the app (the resulting APK will be placed in the `bin/` directory):

    $ sbt package

To build and run the app on device (assuming you have `adb` and developer mode enabled):

    $ sbt run

### Debugging

To see debug outputs, set allowed logging priority of `lcamera` tag to `DEBUG`:

    $ adb shell setprop log.tag.lcamera DEBUG

## Issues

Please report any bugs or feature requests on GitHub's issue tracker.

## License

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.

