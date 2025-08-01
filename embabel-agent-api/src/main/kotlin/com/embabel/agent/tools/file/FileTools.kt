/*
 * Copyright 2024-2025 Embabel Software, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.tools.file

import com.embabel.agent.api.common.support.SelfToolCallbackPublisher
import com.embabel.agent.tools.DirectoryBased
import com.embabel.agent.tools.file.FileWriteTools.FileModification
import com.embabel.common.util.StringTransformer
import com.embabel.common.util.loggerFor
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.zip.ZipInputStream

/**
 * Read and Write file tools. Extend FileReadTools for safe read only use
 */
interface FileTools : FileReadTools, FileWriteTools {

    companion object {

        /**
         * Create a FileReadTools instance with the given root directory.
         */
        fun readOnly(
            root: String,
            fileContentTransformers: List<StringTransformer> = emptyList(),
        ): FileReadTools {
            return object : FileReadTools {
                override val root: String = root
                override val fileContentTransformers: List<StringTransformer> = emptyList()
            }
        }

        /**
         * Create a readwrite FileTools instance with the given root directory.
         */
        fun readWrite(
            root: String,
            fileContentTransformers: List<StringTransformer> = emptyList(),
        ): FileTools = DefaultFileTools(root, fileContentTransformers)
    }
}

private class DefaultFileTools(
    override val root: String,
    override val fileContentTransformers: List<StringTransformer> = emptyList(),
) : FileTools, FileChangeLog by DefaultFileChangeLog()

data class ChangeLog(
    val changes: List<FileWriteTools.FileModification>,
    val root: String,
)

/**
 * LLM-ready ToolCallbacks and convenience methods for file operations.
 * Use at your own risk: This makes changes to your host machine!!
 */
interface FileReadTools : DirectoryBased, SelfToolCallbackPublisher {

    /**
     * Provide sanitizers that run on file content before returning it.
     * They must be sure not to change any content that may need to be replaced
     * as this will break editing if editing is done in the same session.
     */
    val fileContentTransformers: List<StringTransformer>

    /**
     * Does this file exist?
     */
    fun exists(): Boolean {
        return Files.exists(resolvePath(""))
    }

    @Tool(description = "Find files using glob patterns. Return absolute paths")
    fun findFiles(glob: String): List<String> = findFiles(glob, findHighest = false)

    /**
     * Find files using glob patterns.
     * @param glob the glob pattern to match files against
     * @param findHighest if true, only the highest matching file in the directory tree will be returned
     * For example, if you want to find all Maven projects by looking for pom.xml files.
     */
    fun findFiles(glob: String, findHighest: Boolean): List<String> {
        val basePath = Paths.get(root).toAbsolutePath().normalize()
        val syntaxAndPattern = if (glob.startsWith("glob:") || glob.startsWith("regex:")) glob else "glob:$glob"
        val matcher = FileSystems.getDefault().getPathMatcher(syntaxAndPattern)
        val results = mutableListOf<String>()

        if (!findHighest) {
            return Files.walk(basePath).use { paths ->
                paths.filter { matcher.matches(basePath.relativize(it)) }
                    .map { it.toAbsolutePath().toString() }
                    .toList()
            }
        }

        // We cannot rely on Files.walk with findHighest because it works depth first
        val excludedPaths = mutableSetOf<String>()
        val queue = ArrayDeque<Path>().apply { offer(basePath) }

        while (queue.isNotEmpty()) {
            val path = queue.poll()
            val pathStr = path.toAbsolutePath().toString()

            if (excludedPaths.any { pathStr.startsWith("$it${File.separator}") }) continue

            if (matcher.matches(basePath.relativize(path))) {
                results.add(pathStr)
                excludedPaths.add(path.parent.toAbsolutePath().toString())
                continue
            }

            try {
                Files.newDirectoryStream(path).use { stream ->
                    stream.forEach { subPath ->
                        if (Files.isDirectory(subPath)) {
                            queue.offer(subPath)
                        } else if (matcher.matches(basePath.relativize(subPath))) {
                            results.add(subPath.toAbsolutePath().toString())
                            excludedPaths.add(subPath.parent.toAbsolutePath().toString())
                        }
                    }
                }
            } catch (_: IOException) { /* Skip unreadable directories */
            }
        }

        return results
    }

    /**
     * Use for safe reading of files. Returns null if the file doesn't exist or is not readable.
     */
    fun safeReadFile(path: String): String? = try {
        readFile(path)
    } catch (e: Exception) {
        loggerFor<FileReadTools>().warn("Failed to read file at {}: {}", path, e.message)
        null
    }

    @Tool(description = "Read a file at the relative path")
    fun readFile(path: String): String {
        val resolvedPath = resolveAndValidateFile(path)
        val rawContent = Files.readString(resolvedPath)
        val transformedContent =
            StringTransformer.transform(rawContent, fileContentTransformers)

        loggerFor<FileReadTools>().debug(
            "Transformed {} content with {} sanitizers: Length went from {} to {}",
            path,
            fileContentTransformers.size,
            "%,d".format(rawContent.length),
            "%,d".format(transformedContent.length),
        )

        return transformedContent
    }

    @Tool(description = "List files and directories at a given path. Prefix is f: for file or d: for directory")
    fun listFiles(path: String): List<String> {
        val resolvedPath = resolvePath(path)
        if (!Files.exists(resolvedPath)) {
            throw IllegalArgumentException("Directory does not exist: $path, root=$root")
        }
        if (!Files.isDirectory(resolvedPath)) {
            throw IllegalArgumentException("Path is not a directory: $path, root=$root")
        }

        return Files.list(resolvedPath).use { stream ->
            stream.map {
                val prefix = if (Files.isDirectory(it)) "d:" else "f:"
                prefix + it.fileName.toString()
            }.sorted().toList()
        }
    }

    fun resolvePath(path: String): Path {
        return resolvePath(root, path)
    }

    fun resolveAndValidateFile(path: String): Path {
        return resolveAndValidateFile(root, path)
    }

}

interface FileChangeLog {

    fun flushChanges()

    fun recordChange(c: FileModification)

    fun getChanges(): List<FileModification>
}

/**
 * Convenient file change log implementation that stores changes in memory
 * and correctly handles duplicates.
 */
class DefaultFileChangeLog(
    private val changes: MutableList<FileModification> = mutableListOf(),
) : FileChangeLog {

    override fun flushChanges() {
        changes.clear()
        loggerFor<FileWriteTools>().debug("Flushed file changes")
        changes.clear()
    }

    override fun recordChange(c: FileModification) {
        val existingChange = changes.find { it.path == c.path }
        if (existingChange != null) {
            if (existingChange.type == c.type) {
                // If the same change is already recorded, do not add it again
                loggerFor<FileWriteTools>().debug("Change already recorded: {}", c)
            } else {
                // If a different type of change is recorded, update it
                changes.remove(existingChange)
                changes.add(c)
            }
        } else {
            changes.add(c)
        }
        loggerFor<FileWriteTools>().debug("Recorded file change: {}", c)
    }

    override fun getChanges(): List<FileModification> = changes.toList()
}

/**
 * All file modifications must go through this interface.
 */
interface FileWriteTools : DirectoryBased, FileChangeLog, SelfToolCallbackPublisher {

    enum class FileModificationType {
        CREATE, EDIT, DELETE, APPEND, CREATE_DIRECTORY
    }

    data class FileModification(
        val path: String,
        val type: FileModificationType,
    )

    @Tool(description = "Create a file with the given content")
    fun createFile(path: String, content: String): String {
        createFile(path, content, overwrite = false)
        recordChange(FileModification(path, FileModificationType.CREATE))
        return "file created"
    }

    fun createFile(path: String, content: String, overwrite: Boolean) {
        val resolvedPath = resolvePath(root, path)
        if (Files.exists(resolvedPath) && !overwrite) {
            logger.warn("File already exists at {}", path)
            throw IllegalArgumentException("File already exists: $path")
        }

        // Ensure parent directories exist
        Files.createDirectories(resolvedPath.parent)
        Files.writeString(resolvedPath, content)
    }

    @Tool(description = "Edit the file at the given location. Replace oldContent with newContent. oldContent is typically just a part of the file. e.g. use it to replace a particular method to add another method")
    fun editFile(
        path: String,
        @ToolParam(description = "content to replace") oldContent: String,
        @ToolParam(description = "replacement content") newContent: String
    ): String {
        logger.info("Editing file at path {}", path)
        logger.debug("File edit at path {}: {} -> {}", path, oldContent, newContent)
        val resolvedPath = resolveAndValidateFile(root = root, path = path)

        val oldFileContent = Files.readString(resolvedPath)
        val newFileContent = oldFileContent.replace(oldContent, newContent)

        return if (newFileContent == oldFileContent) {
            logger.warn(
                "editFile on {} produced no changes: oldContent=[{}], newContent=[{}]",
                resolvedPath,
                oldContent,
                newContent,
            )
            "no changes made"
        } else {
            Files.writeString(resolvedPath, newFileContent)
            logger.info("Edited file at {}", path)
            recordChange(FileModification(path, FileModificationType.EDIT))
            return "file edited"
        }
    }

    // April 25 2005: This method is the first method added to
    // an Embabel project by an Embabel agent
    @Tool(description = "Create a directory at the given path")
    fun createDirectory(path: String): String {
        val resolvedPath = resolvePath(root = root, path = path)
        if (Files.exists(resolvedPath)) {
            if (Files.isDirectory(resolvedPath)) {
                return "directory already exists"
            }
            throw IllegalArgumentException("A file already exists at this path: $path")
        }

        Files.createDirectories(resolvedPath)
        logger.info("Created directory at path: $path")
        recordChange(FileModification(path, FileModificationType.CREATE_DIRECTORY))
        return "directory created"
    }

    @Tool(description = "Append content to an existing file. The file must already exist.")
    fun appendFile(path: String, content: String): String {
        val resolvedPath = resolveAndValidateFile(root = root, path = path)
        Files.write(resolvedPath, content.toByteArray(), java.nio.file.StandardOpenOption.APPEND)
        logger.info("Appended content to file at path: $path")
        recordChange(FileModification(path, FileModificationType.APPEND))
        return "content appended to file"
    }

    /**
     * Append content to a file, creating it if it doesn't exist.
     * If create is true, the file will be created if it doesn't exist.
     * If createIfNotExists is false, an exception will be thrown if the file doesn't exist.
     */
    fun appendToFile(path: String, content: String, createIfNotExists: Boolean) {
        if (createIfNotExists) {
            try {
                createFile(path, content, overwrite = false)
                return
            } catch (_: IllegalArgumentException) {
                // Ignore if the file already exists
            }
        }
        appendFile(path, content)
    }

    @Tool(description = "Delete a file at the given path")
    fun delete(path: String): String {
        val resolvedPath = resolveAndValidateFile(root = root, path = path)
        Files.delete(resolvedPath)
        logger.info("Deleted file at path: $path")
        recordChange(FileModification(path, FileModificationType.DELETE))
        return "file deleted"
    }


    companion object {

        private val logger = LoggerFactory.getLogger(FileTools::class.java)

        /**
         * Create a temporary directory using the given seed
         */
        fun createTempDir(seed: String): File {
            val tempDir = Files.createTempDirectory(seed).toFile()
            val tempDirPath = tempDir.absolutePath
            logger.info("Created temporary directory at {}", tempDirPath)
            return tempDir
        }

        /**
         * Extract zip file to a temporary directory
         * @param zipFile the zip file to extract
         * @param tempDir directory to extract it under
         * @param delete if true, delete the zip file after extraction
         * @return the path to the extracted file content
         */
        fun extractZipFile(
            zipFile: File,
            tempDir: File,
            delete: Boolean,
        ): File {
            val projectDir = tempDir
            ZipInputStream(FileInputStream(zipFile)).use { zipInputStream ->
                var zipEntry = zipInputStream.nextEntry
                while (zipEntry != null) {
                    val newFile = File(projectDir, zipEntry.name)

                    // Create directories if needed
                    if (zipEntry.isDirectory) {
                        newFile.mkdirs()
                    } else {
                        // Create parent directories if needed
                        newFile.parentFile.mkdirs()

                        // Extract file
                        FileOutputStream(newFile).use { fileOutputStream ->
                            zipInputStream.copyTo(fileOutputStream)
                        }
                    }

                    zipInputStream.closeEntry()
                    zipEntry = zipInputStream.nextEntry
                }
            }

            logger.info("Extracted zip file project to {}", projectDir.absolutePath)

            if (delete) {
                zipFile.delete()
            }
            return File(projectDir, zipFile.nameWithoutExtension)
        }
    }

}

/**
 * Resolves a relative path against the root directory
 * Prevents path traversal attacks by ensuring the resolved path is within the root
 */
private fun resolvePath(root: String, path: String): Path {
    val basePath = Paths.get(root).toAbsolutePath().normalize()
    val resolvedPath = basePath.resolve(path).normalize().toAbsolutePath()

    if (!resolvedPath.startsWith(basePath)) {
        throw SecurityException("Path traversal attempt detected: $path, root=$root, resolved='$resolvedPath', base=$'basePath'")
    }
    return resolvedPath
}

/**
 * Resolves a path and validates that it exists and is a regular file
 * @throws IllegalArgumentException if the file doesn't exist or isn't a regular file
 */
private fun resolveAndValidateFile(root: String, path: String): Path {
    val resolvedPath = resolvePath(root = root, path = path)
    if (!Files.exists(resolvedPath)) {
        throw IllegalArgumentException("File does not exist: $path, root=$root")
    }
    if (!Files.isRegularFile(resolvedPath)) {
        throw IllegalArgumentException("Path is not a regular file: $path, root=$root")
    }
    return resolvedPath
}
