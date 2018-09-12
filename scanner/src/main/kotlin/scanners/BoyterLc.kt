/*
 * Copyright (C) 2017-2018 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.ort.scanner.scanners

import ch.frankel.slf4k.*

import com.fasterxml.jackson.databind.JsonNode

import com.here.ort.model.EMPTY_JSON_NODE
import com.here.ort.model.LicenseFinding
import com.here.ort.model.Provenance
import com.here.ort.model.ScanResult
import com.here.ort.model.ScanSummary
import com.here.ort.model.ScannerDetails
import com.here.ort.model.config.ScannerConfiguration
import com.here.ort.model.jsonMapper
import com.here.ort.scanner.LocalScanner
import com.here.ort.scanner.AbstractScannerFactory
import com.here.ort.scanner.HTTP_CACHE_PATH
import com.here.ort.scanner.ScanException
import com.here.ort.utils.CommandLineTool2
import com.here.ort.utils.OkHttpClientHelper
import com.here.ort.utils.OS
import com.here.ort.utils.log
import com.here.ort.utils.unpack

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.time.Instant

import okhttp3.Request

import okio.Okio

object BoyterLcCommand : CommandLineTool2() {
    override fun command(workingDir: File?) = if (OS.isWindows) "lc.exe" else "lc"

    override fun transformVersion(output: String) =
            // "lc --version" returns a string like "licensechecker version 1.1.1", so simply remove the prefix.
            output.substringAfter("licensechecker version ")
}

class BoyterLc(config: ScannerConfiguration) : LocalScanner(config, BoyterLcCommand) {
    class Factory : AbstractScannerFactory<BoyterLc>() {
        override fun create(config: ScannerConfiguration) = BoyterLc(config)
    }

    // This is used in bootstrap() which is called by the base classes' constructor, so it has to have a getter to get
    // dynamically evaluated before this scanner is fully initialized.
    override val requiredVersion
        get() = "1.3.1"

    override val resultFileExt = "json"

    val CONFIGURATION_OPTIONS = listOf(
            "--confidence", "0.95", // Cut-off value to only get most relevant matches.
            "--format", "json"
    )

    override fun bootstrap(): File {
        val platform = when {
            OS.isLinux -> "x86_64-unknown-linux"
            OS.isMac -> "x86_64-apple-darwin"
            OS.isWindows -> "x86_64-pc-windows"
            else -> throw IllegalArgumentException("Unsupported operating system.")
        }

        val url = "https://github.com/boyter/lc/releases/download/v$requiredVersion/lc-$requiredVersion-$platform.zip"

        log.info { "Downloading $this from '$url'... " }

        val request = Request.Builder().get().url(url).build()

        return OkHttpClientHelper.execute(HTTP_CACHE_PATH, request).use { response ->
            val body = response.body()

            if (response.code() != HttpURLConnection.HTTP_OK || body == null) {
                throw IOException("Failed to download $this from $url.")
            }

            if (response.cacheResponse() != null) {
                log.info { "Retrieved $this from local cache." }
            }

            val scannerArchive = createTempFile(suffix = url.substringAfterLast("/"))
            Okio.buffer(Okio.sink(scannerArchive)).use { it.writeAll(body.source()) }

            val unpackDir = createTempDir()
            unpackDir.deleteOnExit()

            log.info { "Unpacking '$scannerArchive' to '$unpackDir'... " }
            scannerArchive.unpack(unpackDir)

            if (!OS.isWindows) {
                // The Linux version is distributed as a ZIP, but our ZIP unpacker seems to be unable to properly handle
                // Unix mode bits.
                File(unpackDir, scanner.command()).setExecutable(true)
            }

            unpackDir
        }
    }

    override fun getConfiguration() = CONFIGURATION_OPTIONS.joinToString(" ")

    override fun scanPath(scannerDetails: ScannerDetails, path: File, provenance: Provenance, resultsFile: File)
            : ScanResult {
        try {
            val startTime = Instant.now()
            scanner.run(*CONFIGURATION_OPTIONS.toTypedArray(), "--output", resultsFile.absolutePath, path.absolutePath)
            val endTime = Instant.now()

            val result = getResult(resultsFile)
            val summary = generateSummary(startTime, endTime, result)
            return ScanResult(provenance, scannerDetails, summary, result)
        } catch (e: IOException) {
            throw ScanException(e.message)
        }
    }

    override fun getResult(resultsFile: File): JsonNode {
        return if (resultsFile.isFile && resultsFile.length() > 0L) {
            jsonMapper.readTree(resultsFile)
        } else {
            EMPTY_JSON_NODE
        }
    }

    override fun generateSummary(startTime: Instant, endTime: Instant, result: JsonNode): ScanSummary {
        val findings = sortedSetOf<LicenseFinding>()

        result.forEach { file ->
            file["LicenseGuesses"].mapTo(findings) { license ->
                LicenseFinding(license["LicenseId"].textValue())
            }
        }

        return ScanSummary(startTime, endTime, result.size(), findings, errors = mutableListOf())
    }
}
