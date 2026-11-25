# V5PathJNI

V5PathJNI comes from V5-Client/V5Loader, commit
4e9dccc868d7bc251a699e3ec65a504de40b5e52:
https://github.com/V5-Client/V5Loader/tree/4e9dccc868d7bc251a699e3ec65a504de40b5e52/NativeSrc

The V5 project is at https://github.com/V5-Client/V5.
V5PathJNI is distributed under the GNU General Public License, version 3.
See LICENSE in this directory for the full license text. Ryzn matches the
upstream native source, CMake file, and six desktop binaries at the revision
above. Ryzn packages the binaries under its own asset namespace and provides
Java declarations for the JNI interface.

To build from source, set JAVA_HOME to a JDK and run these commands from
this directory with CMake and a C++17 toolchain for the target platform:

    cmake -S . -B build
    cmake --build build --config Release

Distributions of the Ryzn JAR include this directory under
META-INF/v5pathjni-source/ and the standalone GPL text under
META-INF/licenses/V5PathJNI-GPL-3.0.txt.

The following upstream notice is retained for the ChatTriggers code/API
used by V5Loader and referenced by the JNI interface.
## [ChatTriggers](https://github.com/ChatTriggers/ctjs)
MIT License

Copyright (c) 2024 ChatTriggers

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
