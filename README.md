# L Camera

![Screenshot](screenshot.jpg?raw=true)

**L Camera** is an open-source experimental camera app for Android L devices using the new `android.hardware.camera2` api. Currently, the only supported device is Nexus 5 running Android L preview.

*Please note that this app is intended to test and study new features of the camera API, it is not for general uses as it lacks many basic camera features (location tagging, white balance, photo review, flash control, video recording, etc).*

## Features

* True manual focus (adjustable focus distance)
* Manual exposure time (1/2, 1/4, 1/6, 1/8, 1/15, 1/30, 1/60, 1/100, 1/125, 1/250, 1/500, 1/1000, 1/2000, 1/4000, 1/8000)
* Manual ISO (100, 200, 400, 800, 1600, 3200, 6400, 10000)
* DNG output support
* Material design

## Installation

You can either install the pre-built debug APK (`lcamera-debug.apk`) found in the repository, or build and install the APK by yourself.

## Usage

Use it like any camera. Tapping anywhere on the preview screen to focus on a specific point (if auto focus is on), and this will also trigger the white balance/exposure metering sequence if auto exposure setting is on. Clicking the floating action button will bring up auto focus and auto exposure settings (and more to come, hopefully), which can be turned on and off. Turning off the auto focus will allow you to control the focus distance manually. You can also disable auto exposure and manually set the exposure time and ISO.

While capturing, both DNG and JPEG files will be saved in `/sdcard/DCIM/Camera/` directory. Note that each DNG image is 15.36 MiB in size, so make sure you have plenty of free space available!

### What to do with DNG files

Most RAW post-processing programs should be able to open them. While Adobe Lightroom is probably the most popular RAW editor, both [darktable](http://www.darktable.org/) (Linux, Mac) and [RawTherapee](http://rawtherapee.com/) (Windows, Linux, Mac) are free alternatives that offers very powerful editing capabilities.

## How to build

You must have both **scala 2.11.2** and **sbt >= 0.13** installed.

To build the app (the resulting APK will be placed in the `bin/` directory):

    $ sbt package

To build and run the app on device (assuming you have `adb` and developer mode enabled):

    $ sbt run

## Hacking/Technical

The app is written in the [Scala](http://www.scala-lang.org/) programming language and uses the following libraries/tools:

* [SBT](http://www.scala-sbt.org/): build tool.
* [Android SDK Plugin for SBT](https://github.com/pfn/android-sdk-plugin): for building Android apps with SBT.
* [Scaloid](https://github.com/pocorall/scaloid/): for UI layout and various helpers.
* [Scala.Rx](https://github.com/lihaoyi/scala.rx): for reactive value propagation.
* [Floating Action Button](https://github.com/makovkastar/FloatingActionButton): for the floating action button (FAB) featured in material design.

Currently it is needed to create a new capture session for the actual capture, as reusing the preview session for capture outputs garbaged JPEG images (and interestingly the `RAW_SENSOR` output is not corrupted). This  will introduce additional shutter delay (<100ms on Nexus 5) into capture. Maybe there should be another mode with only RAW output which can directly grab images off the preview session.

The preview code probably needs some major refactoring to avoid calling `setRepeatingRequest()`, so we don't end up with unwanted requests in the pipeline.

To see debug outputs, set allowed logging priority of `lcamera` tag to `DEBUG`:

    $ adb shell setprop log.tag.lcamera DEBUG

## To-do

* Add burst capturing mode (30fps @ 8mp on Nexus 5, supposedly)
 * Preliminary test seems to show that it is not possible to with JPEG outputs, try `ImageFormat.YUV_420_888`?
 * Exposure/focus bracketing anyone?
* Add exposure compensation.
* Support portrait orientation.
* Simulate exposure compensation in preview? Currently setting a long shutter speed will throttle the preview frame rate.

## Known Issues

* The new camera API is pretty unstable and will sometimes crash/reboot the device for no reason.
* Sometimes the lens will refuse to move while capturing, no matter how you specify the capture request.
* `source file '<path>/gen/com/melnykov/fab/R.java' could not be found` error while building
 * Just run the build command again.
* AWB does not work in manual exposure mode. You can workaround the problem by setting the white balance in AE mode and switching back to manual mode.

Please report any bugs on GitHub's issue tracker.

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