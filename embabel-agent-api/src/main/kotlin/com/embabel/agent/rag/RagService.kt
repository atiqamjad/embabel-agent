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
package com.embabel.agent.rag

import com.embabel.common.core.types.Described
import com.embabel.common.core.types.HasInfoString

/**
 * Central interface for Retrieval-Augmented Generation (RAG) services.
 * Returns entities as well as text chunks.
 */
interface RagService : Described, HasInfoString {

    /**
     * Human-readable name of the RAG service.
     */
    val name: String

    /**
     * Make a RAG request
     */
    fun search(ragRequest: RagRequest): RagResponse

    companion object {

        /**
         * Return a RAG service that will never return any results
         */
        @JvmStatic
        @JvmOverloads
        fun empty(
            name: String = "empty",
            description: String = "empty",
        ): RagService {
            return EmptyRagService(
                name = name,
                description = description,
            )
        }
    }
}

private data class EmptyRagService(
    override val name: String,
    override val description: String,
) : RagService {

    override fun search(ragRequest: RagRequest): RagResponse {
        return RagResponse(
            service = name,
            results = emptyList(),
        )
    }

    override fun infoString(verbose: Boolean?): String {
        return "Empty RAG service: $name"
    }
}
