/*
 * Copyright 2023 OICR and UCSC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.dockstore.webservice.helpers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.UnsupportedEncodingException;
import org.junit.jupiter.api.Test;

class S3ClientHelperTest {
    /**
     * TODO: Remove this once ToolTester and this webservice's convertToolIdToPartialKey() function is merged and reused
     * @throws UnsupportedEncodingException
     */
    @Test
    void convertToolIdToPartialKey() throws UnsupportedEncodingException {
        String toolId = "#workflow/github.com/ENCODE-DCC/pipeline-container/encode-mapping-cwl";
        assertEquals("workflow/github.com/ENCODE-DCC/pipeline-container%2Fencode-mapping-cwl", S3ClientHelper.convertToolIdToPartialKey(toolId));
        toolId = "#workflow/github.com/ENCODE-DCC/pipeline-container";
        assertEquals("workflow/github.com/ENCODE-DCC/pipeline-container", S3ClientHelper.convertToolIdToPartialKey(toolId));
        toolId = "quay.io/pancancer/pcawg-bwa-mem-workflow";
        assertEquals("tool/quay.io/pancancer/pcawg-bwa-mem-workflow", S3ClientHelper.convertToolIdToPartialKey(toolId));
        toolId = "quay.io/pancancer/pcawg-bwa-mem-workflow/thing";
        assertEquals("tool/quay.io/pancancer/pcawg-bwa-mem-workflow%2Fthing", S3ClientHelper.convertToolIdToPartialKey(toolId));
    }

    @Test
    void testGetToolId() {
        // Key of workflow with no workflow name
        String s3Key = "workflow/github.com/ENCODE-DCC/pipeline-container/1.0/terra/foo.json";
        assertEquals("#workflow/github.com/ENCODE-DCC/pipeline-container", S3ClientHelper.getToolId(s3Key));
        // Key of workflow with workflow name
        s3Key = "workflow/github.com/ENCODE-DCC/pipeline-container%2Fencode-mapping-cwl/1.0/terra/foo.json";
        assertEquals("#workflow/github.com/ENCODE-DCC/pipeline-container/encode-mapping-cwl", S3ClientHelper.getToolId(s3Key));

        // Key of tool with no tool name
        s3Key = "tool/quay.io/pancancer/pcawg-bwa-mem-workflow";
        assertEquals("quay.io/pancancer/pcawg-bwa-mem-workflow", S3ClientHelper.getToolId(s3Key));
        // Key of tool with tool name
        s3Key = "tool/quay.io/pancancer/pcawg-bwa-mem-workflow%2Fthing";
        assertEquals("quay.io/pancancer/pcawg-bwa-mem-workflow/thing", S3ClientHelper.getToolId(s3Key));
    }

    @Test
    void testGetVersionName() {
        // Key of workflow with no workflow name
        String s3Key = "workflow/github.com/ENCODE-DCC/pipeline-container/1.0/terra/foo.json";
        assertEquals("1.0", S3ClientHelper.getVersionName(s3Key));
        // Key of workflow with workflow name
        s3Key = "workflow/github.com/ENCODE-DCC/pipeline-container%2Fencode-mapping-cwl/1.0/terra/foo.json";
        assertEquals("1.0", S3ClientHelper.getVersionName(s3Key));

        // Key of tool with no tool name
        s3Key = "tool/quay.io/pancancer/pcawg-bwa-mem-workflow/1.0/agc/foo.json";
        assertEquals("1.0", S3ClientHelper.getVersionName(s3Key));
        // Key of tool with tool name
        s3Key = "tool/quay.io/pancancer/pcawg-bwa-mem-workflow%2Fthing/1.0/agc/foo.json";
        assertEquals("1.0", S3ClientHelper.getVersionName(s3Key));
    }

    @Test
    void testGetPlatform() {
        // Key of workflow with no workflow name
        String s3Key = "workflow/github.com/ENCODE-DCC/pipeline-container/1.0/terra/foo.json";
        assertEquals("terra", S3ClientHelper.getPlatform(s3Key));
        // Key of workflow with workflow name
        s3Key = "workflow/github.com/ENCODE-DCC/pipeline-container%2Fencode-mapping-cwl/1.0/terra/foo.json";
        assertEquals("terra", S3ClientHelper.getPlatform(s3Key));

        // Key of tool with no tool name
        s3Key = "tool/quay.io/pancancer/pcawg-bwa-mem-workflow/1.0/agc/foo.json";
        assertEquals("agc", S3ClientHelper.getPlatform(s3Key));
        // Key of tool with tool name
        s3Key = "tool/quay.io/pancancer/pcawg-bwa-mem-workflow%2Fthing/1.0/agc/foo.json";
        assertEquals("agc", S3ClientHelper.getPlatform(s3Key));
    }

    @Test
    void testGetFileName() {
        // Key of workflow with no workflow name
        String s3Key = "workflow/github.com/ENCODE-DCC/pipeline-container/1.0/terra/foo.json";
        assertEquals("foo.json", S3ClientHelper.getFileName(s3Key));
        // Key of workflow with workflow name
        s3Key = "workflow/github.com/ENCODE-DCC/pipeline-container%2Fencode-mapping-cwl/1.0/terra/foo.json";
        assertEquals("foo.json", S3ClientHelper.getFileName(s3Key));

        // Key of tool with no tool name
        s3Key = "tool/quay.io/pancancer/pcawg-bwa-mem-workflow/1.0/agc/foo.json";
        assertEquals("foo.json", S3ClientHelper.getFileName(s3Key));
        // Key of tool with tool name
        s3Key = "tool/quay.io/pancancer/pcawg-bwa-mem-workflow%2Fthing/1.0/agc/foo.json";
        assertEquals("foo.json", S3ClientHelper.getFileName(s3Key));
    }
}