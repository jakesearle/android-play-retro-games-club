# Third-party notices

## Code ported to Kotlin

### rcheevos

The RetroAchievements hashing in `app/src/main/kotlin/app/retrolauncher/club/RaHash.kt`
and `club/disc/DiscHash.kt` is a Kotlin port of parts of
[rcheevos](https://github.com/RetroAchievements/rcheevos) (`src/rhash`).

```
MIT License

Copyright (c) 2018 RetroAchievements.org

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
```

### libchdr

The CHD reader in `app/src/main/kotlin/app/retrolauncher/club/disc/Chd.kt` and
`club/disc/Bits.kt` (hunk map, Huffman decoding, CD codecs and ECC regeneration) is a
Kotlin port of parts of [libchdr](https://github.com/rtissera/libchdr).

```
Copyright Romain Tisserand
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * Neither the name of the <organization> nor the
      names of its contributors may be used to endorse or promote products
      derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### Dolphin's RVZ format

The RVZ reader in `club/disc/Rvz.kt` was written from Dolphin's published description of the
format (`docs/WiaAndRvz.md` in [Dolphin](https://github.com/dolphin-emu/dolphin)), checking
details against its reader; no Dolphin code is included.

## Libraries

Used as Gradle dependencies; each ships its own licence.

| Library | Licence |
|---|---|
| [XZ for Java](https://tukaani.org/xz/java.html) (LZMA decoding for CHD) | 0BSD |
| [jsoup](https://jsoup.org/) (reading the club's page) | MIT |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) (reading .7z ROMs), with Commons IO, Lang and Codec | Apache-2.0 |
| [zstd-jni](https://github.com/luben/zstd-jni) and [Zstandard](https://github.com/facebook/zstd) (reading RVZ GameCube images) | BSD-2-Clause, BSD-3-Clause |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | Apache-2.0 |
| AndroidX and Jetpack Compose | Apache-2.0 |

## Data downloaded at runtime

Not included in this repository:

- The [Cocoon emulator catalogue](https://github.com/KansoMirai/CocoonFE) (`android.json`),
  which has no licence and so isn't bundled.
- The [Play Retro Games Club](https://www.joeysretrohandhelds.com/play-retro-games-club/) page.
- RetroAchievements' ROM hash lists and box art, and, once you sign in, your progress and
  games' achievement lists from its [Web API](https://api-docs.retroachievements.org).
- The [Obtainium Emulation Pack](https://github.com/RJNY/Obtainium-Emulation-Pack)
  (Unlicense), for the emulators suggested when a pick has none.
