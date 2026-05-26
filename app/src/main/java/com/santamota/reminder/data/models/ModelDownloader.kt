package com.santamota.reminder.data.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads `.task` files from a public HuggingFace mirror into
 * `filesDir/models/`. Progress is reported to [ModelsRepository] so the
 * UI can render a progress bar without subscribing here directly.
 *
 * Why HttpURLConnection over OkHttp / WorkManager:
 *   - No new dependency. The standard library handles HTTPS, redirects
 *     (with .instanceFollowRedirects = true), and streaming.
 *   - For a single ~500 MB foreground download with progress that the
 *     user is actively watching, we don't need WorkManager's
 *     scheduling/retry machinery.
 *   - Resume on app restart is a future iteration; v1 is fail-and-retry.
 */
class ModelDownloader(
    private val repo: ModelsRepository,
) {

    suspend fun download(spec: ModelSpec): Result<File> = withContext(Dispatchers.IO) {
        val outFile = repo.fileFor(spec)
        val tmpFile = File(outFile.parentFile, "${outFile.name}.part")

        runCatching {
            repo.reportProgress(spec.id, DownloadState.Downloading(0L, 0L))

            val url = URL(spec.downloadUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "intelligent-reminder/0.2")
            }
            try {
                val code = conn.responseCode
                check(code in 200..299) { "HTTP $code on ${spec.downloadUrl}" }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
                conn.inputStream.use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastReportPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n == -1) break
                            output.write(buf, 0, n)
                            downloaded += n
                            // Throttle UI updates — only on full-percent change.
                            val pct = if (total > 0) ((downloaded * 100) / total).toInt() else -1
                            if (pct != lastReportPct) {
                                lastReportPct = pct
                                repo.reportProgress(
                                    spec.id,
                                    DownloadState.Downloading(downloaded, total),
                                )
                            }
                        }
                        output.flush()
                    }
                }
            } finally {
                conn.disconnect()
            }

            // Atomically move .part → final file. Prevents partial files from
            // being picked up as "downloaded" on app restart mid-download.
            if (!tmpFile.renameTo(outFile)) {
                tmpFile.copyTo(outFile, overwrite = true)
                tmpFile.delete()
            }
            repo.reportProgress(spec.id, DownloadState.Idle)
            outFile
        }.onFailure { t ->
            tmpFile.delete()
            repo.reportProgress(spec.id, DownloadState.Failed(t.message ?: "unknown error"))
        }
    }
}
