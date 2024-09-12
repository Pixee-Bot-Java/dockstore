/*
 *    Copyright 2018 OICR
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.dockstore.client.cli;

import static io.dockstore.client.cli.GeneralWorkflowRegressionIT.KNOWN_BREAKAGE_MOVING_TO_1_6_0;
import static io.dockstore.common.CommonTestUtilities.OLD_DOCKSTORE_VERSION;
import static io.dockstore.common.CommonTestUtilities.runOldDockstoreClient;

import io.dockstore.client.cli.BaseIT.TestStatus;
import io.dockstore.common.CommonTestUtilities;
import io.dockstore.common.MuteForSuccessfulTests;
import io.dockstore.common.Registry;
import io.dockstore.common.RegressionTest;
import io.dockstore.common.TestUtility;
import io.dropwizard.testing.ResourceHelpers;
import io.github.pixee.security.HostValidator;
import io.github.pixee.security.Urls;
import io.swagger.client.ApiClient;
import io.swagger.client.ApiException;
import io.swagger.client.api.ContainersApi;
import io.swagger.client.model.DockstoreTool;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;
import uk.org.webcompere.systemstubs.stream.SystemErr;
import uk.org.webcompere.systemstubs.stream.SystemOut;

/**
 * Extra confidential integration tests with old dockstore client, don't rely on the type of repository used (Github, Dockerhub, Quay.io, Bitbucket)
 * Tests a variety of different CLI commands that start with 'dockstore tool'
 * Uses an older (CommonTestUtilities.OLD_DOCKSTORE_VERSION) dockstore client
 * Testing Dockstore CLI 1.3.6 at the time of creation
 *
 * @author gluu
 * @since 1.4.0
 */
@ExtendWith(SystemStubsExtension.class)
@ExtendWith(MuteForSuccessfulTests.class)
@ExtendWith(TestStatus.class)
@Tag(RegressionTest.NAME)
class GeneralRegressionIT extends BaseIT {
    @TempDir
    public static File temporaryFolder;
    static URL url;
    static File dockstore;
    private static final String DOCKERHUB_TOOL_PATH = "registry.hub.docker.com/testPath/testUpdatePath/test5";

    @SystemStub
    public final SystemOut systemOut = new SystemOut();
    @SystemStub
    public final SystemErr systemErr = new SystemErr();

    @BeforeAll
    public static void getOldDockstoreClient() throws IOException {
        TestUtility.createFakeDockstoreConfigFile();
        url = Urls.create("https://github.com/dockstore/dockstore-cli/releases/download/" + OLD_DOCKSTORE_VERSION + "/dockstore", Urls.HTTP_PROTOCOLS, HostValidator.DENY_COMMON_INFRASTRUCTURE_TARGETS);
        dockstore = new File(temporaryFolder, "dockstore");
        FileUtils.copyURLToFile(url, dockstore);
        Assertions.assertTrue(dockstore.setExecutable(true));
    }

    @BeforeEach
    @Override
    public void resetDBBetweenTests() throws Exception {
        CommonTestUtilities.addAdditionalToolsWithPrivate2(SUPPORT, false, testingPostgres);
    }

    /**
     * this method will set up the webservice and return the container api
     *
     * @return ContainersApi
     * @throws ApiException comes back from a web service error
     */
    private ContainersApi setupWebService() throws ApiException {
        ApiClient client = getWebClient(USER_2_USERNAME, testingPostgres);
        return new ContainersApi(client);
    }

    /**
     * this method will set up the database and select data needed
     *
     * @return cwl/wdl/dockerfile path of the tool's tag in the database
     * @throws ApiException comes back from a web service error
     */
    private String getPathfromDB(String type) {
        // Set up DB

        // Select data from DB
        final Long toolID = testingPostgres.runSelectStatement("select id from tool where name = 'testUpdatePath'", long.class);
        final Long tagID = testingPostgres.runSelectStatement("select parentid from tag where parentid = " + toolID, long.class);

        return testingPostgres.runSelectStatement("select " + type + " from tag where id = " + tagID, String.class);
    }

    /**
     * Tests adding/editing/deleting container related labels (for search)
     */
    @Test
    void testAddEditRemoveLabelOldClient() {
        // Test adding/removing labels for different containers
        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "label", "--entry",
                "quay.io/dockstoretestuser2/quayandgithub", "--add", "quay", "--add", "github", "--remove", "dockerhub", "--script" });
        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "label", "--entry",
                "quay.io/dockstoretestuser2/quayandgithub", "--add", "github", "--add", "dockerhub", "--remove", "quay", "--script" });

        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "label", "--entry",
                "quay.io/dockstoretestuser2/quayandgithubalternate", "--add", "alternate", "--add", "github", "--script" });
        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "label", "--entry",
                "quay.io/dockstoretestuser2/quayandgithubalternate", "--remove", "github", "--script" });

        final long count = testingPostgres.runSelectStatement("select count(*) from entry_label where entryid = '2'", long.class);
        Assertions.assertEquals(2, count, "there should be 2 labels for the given container, there are " + count);

        final long count2 = testingPostgres.runSelectStatement(
            "select count(*) from label where value = 'quay' or value = 'github' or value = 'dockerhub' or value = 'alternate'",
            long.class);
        Assertions.assertEquals(4, count2, "there should be 4 labels in the database (No Duplicates), there are " + count2);
    }

    /**
     * Tests altering the cwl and dockerfile paths to invalid locations (quick registered)
     */
    @Test
    @Disabled(KNOWN_BREAKAGE_MOVING_TO_1_6_0)
    void testVersionTagWDLCWLAndDockerfilePathsAlterationOldClient() {
        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "version_tag", "update", "--entry",
                "quay.io/dockstoretestuser2/quayandgithub", "--name", "master", "--cwl-path", "/testDir/Dockstore.cwl", "--wdl-path",
                "/testDir/Dockstore.wdl", "--dockerfile-path", "/testDir/Dockerfile", "--script" });

        final long count = testingPostgres.runSelectStatement(
            "select count(*) from tag, tool where tool.registry = '" + Registry.QUAY_IO.getDockerPath()
                + "' and tool.namespace = 'dockstoretestuser2' and tool.name = 'quayandgithub' and tool.toolname IS NULL and tool.id=tag.parentid and valid = 'f'",
            long.class);
        Assertions.assertEquals(1, count, "there should now be an invalid tag, found " + count);

        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "version_tag", "update", "--entry",
                "quay.io/dockstoretestuser2/quayandgithub", "--name", "master", "--cwl-path", "/Dockstore.cwl", "--wdl-path",
                "/Dockstore.wdl", "--dockerfile-path", "/Dockerfile", "--script" });

        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "refresh", "--entry",
                "quay.io/dockstoretestuser2/quayandgithub", "--script" });

        final long count2 = testingPostgres.runSelectStatement(
            "select count(*) from tag, tool where tool.registry = '" + Registry.QUAY_IO.getDockerPath()
                + "' and tool.namespace = 'dockstoretestuser2' and tool.name = 'quayandgithub' and tool.toolname = '' and tool.id=tag.parentid and valid = 'f'",
            long.class);
        Assertions.assertEquals(0, count2, "the invalid tag should now be valid, found " + count2);
    }

    /**
     * Tests adding tag tags to a manually registered container
     */
    @Test
    void testAddVersionTagManualContainerOldClient() {
        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "manual_publish", "--registry",
                Registry.QUAY_IO.name(), "--namespace", "dockstoretestuser2", "--name", "quayandgithub", "--git-url",
                "git@github.com:dockstoretestuser2/quayandgithubalternate.git", "--git-reference", "master", "--toolname", "alternate",
                "--cwl-path", "/testDir/Dockstore.cwl", "--dockerfile-path", "/testDir/Dockerfile", "--script" });

        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "version_tag", "add", "--entry",
                "quay.io/dockstoretestuser2/quayandgithub/alternate", "--name", "masterTest", "--image-id",
                "4728f8f5ce1709ec8b8a5282e274e63de3c67b95f03a519191e6ea675c5d34e8", "--git-reference", "master", "--script" });

        final long count = testingPostgres.runSelectStatement(
            " select count(*) from  tag, tool where tag.parentid = tool.id and giturl ='git@github.com:dockstoretestuser2/quayandgithubalternate.git' and toolname = 'alternate'",
            long.class);
        Assertions.assertEquals(3, count, "there should be 3 tags, 2  that are autogenerated (master and latest) and the newly added masterTest tag, found " + count);

    }

    /**
     * Tests hiding and unhiding different versions of a container (quick registered)
     */
    @Test
    @Disabled(KNOWN_BREAKAGE_MOVING_TO_1_6_0)
    void testVersionTagHideOld() {
        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "version_tag", "update", "--entry",
                "quay.io/dockstoretestuser2/quayandgithub", "--name", "master", "--hidden", "true", "--script" });

        final long count = testingPostgres.runSelectStatement("select count(*) from tag where hidden = 't'", long.class);
        Assertions.assertEquals(1, count, "there should be 1 hidden tag");

        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "version_tag", "update", "--entry",
                "quay.io/dockstoretestuser2/quayandgithub", "--name", "master", "--hidden", "false", "--script" });

        final long count2 = testingPostgres.runSelectStatement("select count(*) from tag where hidden = 't'", long.class);
        Assertions.assertEquals(0, count2, "there should be 0 hidden tag");
    }

    /**
     * Test update tag with only WDL to invalid then valid
     */
    @Test
    @Disabled(KNOWN_BREAKAGE_MOVING_TO_1_6_0)
    void testVersionTagWDLOldClient() {
        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "version_tag", "update", "--entry",
                "quay.io/dockstoretestuser2/quayandgithubwdl", "--name", "master", "--wdl-path", "/randomDir/Dockstore.wdl", "--script" });
        // should now be invalid

        final long count = testingPostgres.runSelectStatement(
            "select count(*) from tag, tool where tool.registry = '" + Registry.QUAY_IO.getDockerPath()
                + "' and tool.namespace = 'dockstoretestuser2' and tool.name = 'quayandgithubwdl' and tool.toolname IS NULL and tool.id=tag.parentid and valid = 'f'",
            long.class);

        Assertions.assertEquals(1, count, "there should now be 1 invalid tag, found " + count);

        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "version_tag", "update", "--entry",
                "quay.io/dockstoretestuser2/quayandgithubwdl", "--name", "master", "--wdl-path", "/Dockstore.wdl", "--script" });
        // should now be valid
        final long count2 = testingPostgres.runSelectStatement(
            "select count(*) from tag, tool where tool.registry = '" + Registry.QUAY_IO.getDockerPath()
                + "' and tool.namespace = 'dockstoretestuser2' and tool.name = 'quayandgithubwdl' and tool.toolname IS NULL and tool.id=tag.parentid and valid = 'f'",
            long.class);
        Assertions.assertEquals(0, count2, "the tag should now be valid");

    }

    /**
     * Will test deleting a tag tag from a manually registered container
     */
    @Test
    void testVersionTagDeleteOldClient() {
        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "manual_publish", "--registry",
                Registry.QUAY_IO.name(), "--namespace", "dockstoretestuser2", "--name", "quayandgithub", "--git-url",
                "git@github.com:dockstoretestuser2/quayandgithubalternate.git", "--git-reference", "master", "--toolname", "alternate",
                "--cwl-path", "/testDir/Dockstore.cwl", "--wdl-path", "/testDir/Dockstore.wdl", "--dockerfile-path", "/testDir/Dockerfile",
                "--script" });

        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "version_tag", "add", "--entry",
                "quay.io/dockstoretestuser2/quayandgithub/alternate", "--name", "masterTest", "--image-id",
                "4728f8f5ce1709ec8b8a5282e274e63de3c67b95f03a519191e6ea675c5d34e8", "--git-reference", "master", "--script" });

        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "version_tag", "remove", "--entry",
                "quay.io/dockstoretestuser2/quayandgithub/alternate", "--name", "masterTest", "--script" });

        final long count = testingPostgres.runSelectStatement("select count(*) from tag where name = 'masterTest'", long.class);
        Assertions.assertEquals(0, count, "there should be no tags with the name masterTest");
    }

    /**
     * Tests that tool2JSON works for entries on Dockstore
     */
    @Test
    void testTool2JsonWdlOldClient() {
        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "publish", "--entry",
                "quay.io/dockstoretestuser2/quayandgithubwdl" });
        // need to publish before converting
        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "convert", "entry2json", "--entry",
                "quay.io/dockstoretestuser2/quayandgithubwdl", "--descriptor", "wdl", "--script" });
        // TODO: Test that output is the expected WDL file
    }

    @Test
    void registerUnregisterAndCopyOldClient() {
        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "publish", "--entry",
                "quay.io/dockstoretestuser2/quayandgithubwdl" });

        boolean published = testingPostgres.runSelectStatement(
            "select ispublished from tool where registry = '" + Registry.QUAY_IO.getDockerPath()
                + "' and namespace = 'dockstoretestuser2' and name = 'quayandgithubwdl';", boolean.class);
        Assertions.assertTrue(published, "tool not published");

        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "publish", "--entry",
                "quay.io/dockstoretestuser2/quayandgithubwdl", "--entryname", "foo" });

        long count = testingPostgres.runSelectStatement("select count(*) from tool where registry = '" + Registry.QUAY_IO.getDockerPath()
            + "' and namespace = 'dockstoretestuser2' and name = 'quayandgithubwdl';", long.class);
        Assertions.assertEquals(2, count, "should be two after republishing");
        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "publish", "--unpub", "--entry",
                "quay.io/dockstoretestuser2/quayandgithubwdl" });

        published = testingPostgres.runSelectStatement("select ispublished from tool where registry = '" + Registry.QUAY_IO.getDockerPath()
            + "' and namespace = 'dockstoretestuser2' and name = 'quayandgithubwdl' and toolname IS NULL;", boolean.class);
        Assertions.assertFalse(published, "tool not unpublished");
    }

    /**
     * Tests that WDL2JSON works for local file
     */
    @Test
    void testWDL2JSONOld() {
        File sourceFile = new File(ResourceHelpers.resourceFilePath("wdl.wdl"));
        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "convert", "wdl2json", "--wdl",
                sourceFile.getAbsolutePath(), "--script" });
        // TODO: Test that output is the expected WDL file
    }

    @Test
    void testCWL2JSONOld() {
        File sourceFile = new File(ResourceHelpers.resourceFilePath("dockstore-tool-bamstats.cwl"));
        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "convert", "cwl2json", "--cwl",
                sourceFile.getAbsolutePath(), "--script" });
        // TODO: Test that output is the expected JSON file
    }

    @Test
    void testCWL2YAMLOld() {
        File sourceFile = new File(ResourceHelpers.resourceFilePath("dockstore-tool-bamstats.cwl"));
        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "convert", "cwl2yaml", "--cwl",
                sourceFile.getAbsolutePath(), "--script" });
        // TODO: Test that output is the expected yaml file
    }

    /**
     * Tests that WDL and CWL files can be grabbed from the command line
     */
    @Test
    @Disabled(KNOWN_BREAKAGE_MOVING_TO_1_6_0)
    void testGetWdlAndCwlOld() {
        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "publish", "--entry",
                "quay.io/dockstoretestuser2/quayandgithubwdl" });
        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "wdl", "--entry",
                "quay.io/dockstoretestuser2/quayandgithubwdl", "--script" });
        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "publish", "--entry",
                "quay.io/dockstoretestuser2/quayandgithub" });
        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "cwl", "--entry",
                "quay.io/dockstoretestuser2/quayandgithub", "--script" });
    }

    /**
     * Tests that a developer can launch a CWL Tool locally, instead of getting files from Dockstore
     */
    @Test
    void testLocalLaunchCWLOld() {
        runOldDockstoreClient(dockstore,
            new String[] { "--config", ResourceHelpers.resourceFilePath("config_file2.txt"), "tool", "launch", "--local-entry",
                ResourceHelpers.resourceFilePath("arrays.cwl"), "--json",
                ResourceHelpers.resourceFilePath("testArrayHttpInputLocalOutput.json"), "--script" });
    }

    /**
     * Test to update the default path of CWL and it should change the tag's CWL path in the database
     *
     * @throws ApiException
     */
    @Test
    void testUpdateToolPathCWL() throws ApiException {
        //setup webservice and get tool api
        ContainersApi toolsApi = setupWebService();

        //register tool
        DockstoreTool toolTest = toolsApi.getContainerByToolPath(DOCKERHUB_TOOL_PATH, null);
        toolsApi.refresh(toolTest.getId());

        //change the default cwl path and refresh
        toolTest.setDefaultCwlPath("/test1.cwl");
        toolsApi.updateTagContainerPath(toolTest.getId(), toolTest);
        toolsApi.refresh(toolTest.getId());

        //check if the tag's dockerfile path have the same cwl path or not in the database
        final String path = getPathfromDB("cwlpath");
        Assertions.assertEquals("/test1.cwl", path, "the cwl path should be changed to /test1.cwl");
    }

    /**
     * Test to update the default path of WDL and it should change the tag's WDL path in the database
     *
     * @throws ApiException
     */
    @Test
    void testUpdateToolPathWDL() throws ApiException {
        //setup webservice and get tool api
        ContainersApi toolsApi = setupWebService();

        //register tool
        DockstoreTool toolTest = toolsApi.getContainerByToolPath(DOCKERHUB_TOOL_PATH, null);

        //change the default wdl path and refresh
        toolTest.setDefaultWdlPath("/test1.wdl");
        toolsApi.updateTagContainerPath(toolTest.getId(), toolTest);
        toolsApi.refresh(toolTest.getId());

        //check if the tag's wdl path have the same wdl path or not in the database
        final String path = getPathfromDB("wdlpath");
        Assertions.assertEquals("/test1.wdl", path, "the cwl path should be changed to /test1.wdl");
    }

    /**
     * Test to update the default path of Dockerfile and it should change the tag's dockerfile path in the database
     *
     * @throws ApiException
     */
    @Test
    void testUpdateToolPathDockerfile() throws ApiException {
        //setup webservice and get tool api
        ContainersApi toolsApi = setupWebService();

        //register tool
        DockstoreTool toolTest = toolsApi.getContainerByToolPath(DOCKERHUB_TOOL_PATH, null);

        //change the default dockerfile and refresh
        toolTest.setDefaultDockerfilePath("/test1/Dockerfile");
        toolsApi.updateTagContainerPath(toolTest.getId(), toolTest);
        toolsApi.refresh(toolTest.getId());

        //check if the tag's dockerfile path have the same dockerfile path or not in the database
        final String path = getPathfromDB("dockerfilepath");
        Assertions.assertEquals("/test1/Dockerfile", path, "the cwl path should be changed to /test1/Dockerfile");
    }
}
