An android contact manager, designed to be easy to extend and customize.

This app is written in Scala, using the Positronic Net library
(version 0.1).  The build procedure, using sbt (the "simple build
tool" --- or so they call it) are something like the following:

First, install the Android SDK, sbt, and the Positronic Net library
itself following instructions [here](http://rst.github.com/tut_sections/2001/01/01/installation.html).

Then compile and build this app:

    $ cd [your workspace]
    $ git clone https://github.com/rst/UmbrellaToday.git
    $ cd positronic_contacts
    $ sbt android:package-debug

From there, the app can be loaded onto the emulator with

    $ sbt android:start-emulator

or on attached hardware with

    $ sbt android:start-emulator

See the documentation on the android sbt plugin for details.
