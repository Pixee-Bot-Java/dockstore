/*
 *    Copyright 2017 OICR
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
package io.dockstore.webservice.languages;

import static java.nio.file.attribute.PosixFilePermissions.asFileAttribute;
import static java.nio.file.attribute.PosixFilePermissions.fromString;

import com.github.zafarkhaja.semver.UnexpectedCharacterException;
import com.github.zafarkhaja.semver.expr.LexerException;
import com.github.zafarkhaja.semver.expr.UnexpectedTokenException;
import com.google.common.base.Strings;
import com.google.common.io.Files;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.common.DockerImageReference;
import io.dockstore.common.DockerParameter;
import io.dockstore.common.LanguageHandlerHelper;
import io.dockstore.common.VersionTypeValidation;
import io.dockstore.common.WdlBridge;
import io.dockstore.webservice.CustomWebApplicationException;
import io.dockstore.webservice.core.Author;
import io.dockstore.webservice.core.DescriptionSource;
import io.dockstore.webservice.core.ParsedInformation;
import io.dockstore.webservice.core.SourceFile;
import io.dockstore.webservice.core.Validation;
import io.dockstore.webservice.core.Version;
import io.dockstore.webservice.core.Workflow;
import io.dockstore.webservice.core.WorkflowVersion;
import io.dockstore.webservice.helpers.CheckUrlInterface;
import io.dockstore.webservice.helpers.CheckUrlInterface.UrlStatus;
import io.dockstore.webservice.helpers.SourceCodeRepoInterface;
import io.dockstore.webservice.helpers.SourceFileHelper;
import io.dockstore.webservice.jdbi.ToolDAO;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BoundedInputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.http.HttpStatus;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import wdl.draft3.parser.WdlParser;
import wdl.draft3.parser.WdlParser.SyntaxError;

/**
 * This class will eventually handle support for understanding WDL
 */
public class WDLHandler implements LanguageHandlerInterface {
    public static final Logger LOG = LoggerFactory.getLogger(WDLHandler.class);
    public static final String ERROR_PARSING_WORKFLOW_YOU_MAY_HAVE_A_RECURSIVE_IMPORT = "Error parsing workflow. You may have a recursive import.";
    public static final String ERROR_PARSING_WORKFLOW_RECURSIVE_LOCAL_IMPORT = "Recursive local import detected: ";
    public static final String WDL_PARSE_ERROR = "Unable to parse WDL workflow, ";
    // According to the WDL 1.0 spec, WDL files without a 'version' field should be treated as 'draft-2'
    public static final String DEFAULT_WDL_VERSION = "draft-2";
    private static final Pattern IMPORT_PATTERN = Pattern.compile("^import\\s+\"(\\S+)\"");

    private static final String LATEST_SUPPORTED_WDL_VERSION = "1.0";
    private static final String DESCRIPTOR_SUFFIX = "descriptor";

    public static void checkForRecursiveLocalImports(String content, Set<SourceFile> sourceFiles, Set<String> absolutePaths, String parent)
            throws ParseException {
        // Use matcher to get imports
        String[] lines = StringUtils.split(content, '\n');
        for (String line : lines) {
            Matcher m = IMPORT_PATTERN.matcher(line);

            while (m.find()) {
                String match = m.group(1);
                if (!match.startsWith("http://") && !match.startsWith("https://")) { // Don't resolve URLs
                    String localRelativePath = match.replaceFirst("file://", "");
                    String absolutePath = LanguageHandlerHelper.unsafeConvertRelativePathToAbsolutePath(parent, localRelativePath);
                    if (absolutePaths.contains(absolutePath)) {
                        throw new ParseException(ERROR_PARSING_WORKFLOW_RECURSIVE_LOCAL_IMPORT + absolutePath, 0);
                    }
                    // Creating a new set to avoid false positive caused by multiple "branches" that have the same import
                    Set<String> newAbsolutePaths = new HashSet<>();
                    newAbsolutePaths.addAll(absolutePaths);
                    newAbsolutePaths.add(absolutePath);
                    Optional<SourceFile> sourcefile = sourceFiles.stream()
                            .filter(sourceFile -> sourceFile.getAbsolutePath().equals(absolutePath)).findFirst();
                    if (sourcefile.isPresent()) {
                        File file = new File(absolutePath);
                        String newParent = file.getParent();
                        checkForRecursiveLocalImports(sourcefile.get().getContent(), sourceFiles, newAbsolutePaths, newParent);
                    }
                }
            }
        }

    }

    @Override
    public Version parseWorkflowContent(String filepath, String content, Set<SourceFile> sourceFiles, Version version) {
        Optional<String> optValidationMessageObject = reportValidationForLocalRecursiveImports(content,
                sourceFiles, filepath);
        if (optValidationMessageObject.isPresent()) {
            Map<String, String> validationMessageObject = new HashMap<>();
            validationMessageObject.put(filepath, optValidationMessageObject.get());
            version.addOrUpdateValidation(new Validation(DescriptorLanguage.FileType.DOCKSTORE_WDL, false, validationMessageObject));
            return version;
        }

        WdlBridge wdlBridge = new WdlBridge();
        final Map<String, String> secondaryFiles = sourceFiles.stream()
                .collect(Collectors.toMap(SourceFile::getAbsolutePath, SourceFile::getContent));
        wdlBridge.setSecondaryFiles(secondaryFiles);
        File tempMainDescriptor = null;
        try {
            tempMainDescriptor = createTempFile("main", DESCRIPTOR_SUFFIX);
            Files.asCharSink(tempMainDescriptor, StandardCharsets.UTF_8).write(content);
            try {
                // Set language version for descriptor source files
                for (SourceFile sourceFile : sourceFiles) {
                    if (sourceFile.getType() == DescriptorLanguage.FileType.DOCKSTORE_WDL) {
                        sourceFile.getMetadata().setTypeVersion(getLanguageVersion(sourceFile.getAbsolutePath(), sourceFiles).orElse(null));
                    }
                }
                version.setDescriptorTypeVersionsFromSourceFiles(sourceFiles);

                List<Map<String, String>> metadata = wdlBridge.getMetadata(tempMainDescriptor.getAbsolutePath(), filepath);
                Queue<String> authors = new LinkedList<>();
                Queue<String> emails = new LinkedList<>();
                Set<Author> newAuthors = new HashSet<>();
                final String[] mainDescription = { null };

                metadata.forEach(metaBlock -> {
                    String author = metaBlock.get("author");
                    String[] callAuthors = author != null ? author.split(",") : null;
                    if (callAuthors != null) {
                        for (String callAuthor : callAuthors) {
                            authors.add(callAuthor.trim());
                        }
                    }

                    String email = metaBlock.get("email");
                    String[] callEmails = email != null ? email.split(",") : null;
                    if (callEmails != null) {
                        for (String callEmail : callEmails) {
                            emails.add(callEmail.trim());
                        }
                    }

                    if (!authors.isEmpty()) {
                        // Only set emails for authors if every author has an email.
                        // Otherwise, ignore emails because we don't know which email belongs to which author
                        if (authors.size() == emails.size()) {
                            while (!authors.isEmpty()) {
                                Author newAuthor = new Author(authors.remove());
                                newAuthor.setEmail(emails.remove());
                                newAuthors.add(newAuthor);
                            }
                        } else {
                            while (!authors.isEmpty()) {
                                Author newAuthor = new Author(authors.remove());
                                newAuthors.add(newAuthor);
                            }
                            emails.clear();
                        }
                    }

                    String description = metaBlock.get("description");
                    if (description != null && !description.isBlank()) {
                        mainDescription[0] = description;
                    }
                });

                // Add authors from descriptor
                for (Author author: newAuthors) {
                    version.addAuthor(author);
                }

                if (!Strings.isNullOrEmpty(mainDescription[0])) {
                    version.setDescriptionAndDescriptionSource(mainDescription[0], DescriptionSource.DESCRIPTOR);
                }
            } catch (WdlParser.SyntaxError ex) {
                LOG.error("Unable to parse WDL file " + filepath, ex);
                Map<String, String> validationMessageObject = new HashMap<>();
                String errorMessage = "WDL file is malformed or missing, cannot extract metadata. " + ex.getMessage();
                errorMessage = getUnsupportedWDLVersionErrorString(content).orElse(errorMessage);
                validationMessageObject.put(filepath, errorMessage);
                version.addOrUpdateValidation(new Validation(DescriptorLanguage.FileType.DOCKSTORE_WDL, false, validationMessageObject));
                version.setDescriptionAndDescriptionSource(null, null);
                version.getAuthors().clear();
                version.getOrcidAuthors().clear();
                return version;
            } catch (StackOverflowError error) {
                throw createStackOverflowThrowable(error);
            }
        } catch (IOException e) {
            throw new CustomWebApplicationException(e.getMessage(), HttpStatus.SC_INTERNAL_SERVER_ERROR);
        } finally {
            FileUtils.deleteQuietly(tempMainDescriptor);
        }
        return version;
    }


    /**
     * A common helper method for checking for local recursive imports
     * @param primaryDescriptorContent content of primary descriptor
     * @param sourcefiles Set of sourcefiles to validate
     * @param primaryDescriptorFilePath Path of primary descriptor
     * @return an optional String
     */
    public Optional<String>  reportValidationForLocalRecursiveImports(String primaryDescriptorContent, Set<SourceFile> sourcefiles,
            String primaryDescriptorFilePath) {
        try {
            String parent = primaryDescriptorFilePath.startsWith("/") ? new File(primaryDescriptorFilePath).getParent() : "/";
            checkForRecursiveLocalImports(primaryDescriptorContent, sourcefiles, new HashSet<>(), parent);
        } catch (ParseException e) {
            LOG.error("Recursive local imports found: ", e);
            return Optional.of(e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * A common helper method for validating tool and workflow sets
     * @param sourcefiles Set of sourcefiles to validate
     * @param primaryDescriptorFilePath Path of primary descriptor
     * @param type workflow or tool
     * @return
     */
    public VersionTypeValidation validateEntrySet(Set<SourceFile> sourcefiles, String primaryDescriptorFilePath, String type) {
        File tempMainDescriptor = null;
        String mainDescriptor = null;

        List<DescriptorLanguage.FileType> fileTypes = new ArrayList<>(Collections.singletonList(DescriptorLanguage.FileType.DOCKSTORE_WDL));
        Set<SourceFile> filteredSourceFiles = filterSourcefiles(sourcefiles, fileTypes);

        Map<String, String> validationMessageObject = new HashMap<>();

        if (filteredSourceFiles.size() > 0) {
            try {
                Optional<SourceFile> primaryDescriptor = filteredSourceFiles.stream()
                    .filter(sourceFile1 -> Objects.equals(sourceFile1.getPath(),
                        primaryDescriptorFilePath))
                    .findFirst();

                if (primaryDescriptor.isPresent()) {
                    if (primaryDescriptor.get().getContent() == null || primaryDescriptor.get().getContent().trim().replaceAll("\n", "").isEmpty()) {
                        validationMessageObject.put(primaryDescriptorFilePath, "The primary descriptor '" + primaryDescriptorFilePath + "' has no content. Please make it a valid WDL document if you want to save.");
                        return new VersionTypeValidation(false, validationMessageObject);
                    }
                    mainDescriptor = primaryDescriptor.get().getContent();
                } else {
                    validationMessageObject.put(primaryDescriptorFilePath, "The primary descriptor '" + primaryDescriptorFilePath + "' could not be found.");
                    return new VersionTypeValidation(false, validationMessageObject);
                }

                Map<String, String> secondaryDescContent = new HashMap<>();
                for (SourceFile sourceFile : filteredSourceFiles) {
                    if (!Objects.equals(sourceFile.getPath(), primaryDescriptorFilePath) && sourceFile.getContent() != null) {
                        if (sourceFile.getContent().trim().replaceAll("\n", "").isEmpty()) {
                            if (Objects.equals(sourceFile.getType(), DescriptorLanguage.FileType.DOCKSTORE_WDL)) {
                                validationMessageObject.put(primaryDescriptorFilePath, "File '" + sourceFile.getPath() + "' has no content. Either delete the file or make it a valid WDL document.");
                            } else if (Objects.equals(sourceFile.getType(), DescriptorLanguage.FileType.WDL_TEST_JSON)) {
                                validationMessageObject.put(primaryDescriptorFilePath, "File '" + sourceFile.getPath() + "' has no content. Either delete the file or make it a valid WDL JSON/YAML file.");
                            } else {
                                validationMessageObject.put(primaryDescriptorFilePath, "File '" + sourceFile.getPath() + "' has no content. Either delete the file or make it valid.");
                            }
                            return new VersionTypeValidation(false, validationMessageObject);
                        }
                        secondaryDescContent.put(sourceFile.getAbsolutePath(), sourceFile.getContent());
                    }
                }
                tempMainDescriptor = createTempFile("main", DESCRIPTOR_SUFFIX);
                Files.asCharSink(tempMainDescriptor, StandardCharsets.UTF_8).write(mainDescriptor);
                String content = FileUtils.readFileToString(tempMainDescriptor, StandardCharsets.UTF_8);
                try {
                    checkForRecursiveHTTPImports(content, new HashSet<>());
                } catch (IOException | CustomWebApplicationException e) {
                    validationMessageObject.put(primaryDescriptorFilePath, e.getMessage());
                    return new VersionTypeValidation(false, validationMessageObject);
                }

                Optional<String> optValidationMessage = reportValidationForLocalRecursiveImports(content,
                        sourcefiles, primaryDescriptorFilePath);
                if (optValidationMessage.isPresent()) {
                    validationMessageObject.put(primaryDescriptorFilePath, optValidationMessage.get());
                    return new VersionTypeValidation(false, validationMessageObject);
                }

                WdlBridge wdlBridge = new WdlBridge();
                wdlBridge.setSecondaryFiles(secondaryDescContent);

                if (Objects.equals(type, "tool")) {
                    wdlBridge.validateTool(tempMainDescriptor.getAbsolutePath(), primaryDescriptorFilePath);
                } else {
                    wdlBridge.validateWorkflow(tempMainDescriptor.getAbsolutePath(), primaryDescriptor.get().getAbsolutePath());
                }
            } catch (WdlParser.SyntaxError | IllegalArgumentException e) {
                if (tempMainDescriptor != null) {
                    validationMessageObject.put(primaryDescriptorFilePath,
                            getUnsupportedWDLVersionErrorString(tempMainDescriptor.getAbsolutePath())
                                .orElse(e.getMessage()));
                } else {
                    validationMessageObject.put(primaryDescriptorFilePath, e.getMessage());
                }
                return new VersionTypeValidation(false, validationMessageObject);
            } catch (CustomWebApplicationException e) {
                throw e;
            } catch (Exception e) {
                LOG.error("Unhandled exception", e);
                throw new CustomWebApplicationException(e.getMessage(), HttpStatus.SC_INTERNAL_SERVER_ERROR);
            } catch (StackOverflowError error) {
                throw createStackOverflowThrowable(error);
            } finally {
                FileUtils.deleteQuietly(tempMainDescriptor);
            }
        } else {
            validationMessageObject.put(primaryDescriptorFilePath, "Primary WDL descriptor is not present.");
            return new VersionTypeValidation(false, validationMessageObject);
        }
        return new VersionTypeValidation(true, Collections.emptyMap());
    }

    /**
     *
     * @param content
     * @param currentFileImports
     * @throws IOException
     */
    public void checkForRecursiveHTTPImports(String content, Set<String> currentFileImports) throws IOException {
        // Use matcher to get imports
        String[] lines = StringUtils.split(content, '\n');

        for (String line : lines) {
            Matcher m = IMPORT_PATTERN.matcher(line);

            while (m.find()) {
                String match = m.group(1);
                if (match.startsWith("http://") || match.startsWith("https://")) { // Don't resolve URLs
                    if (currentFileImports.contains(match)) {
                        throw new CustomWebApplicationException(ERROR_PARSING_WORKFLOW_YOU_MAY_HAVE_A_RECURSIVE_IMPORT,
                                HttpStatus.SC_UNPROCESSABLE_ENTITY);
                    } else {
                        URL url = new URL(match);
                        try (InputStream is = url.openStream();
                            BoundedInputStream boundedInputStream = new BoundedInputStream(is, FileUtils.ONE_MB)) {
                            String fileContents = IOUtils.toString(boundedInputStream, StandardCharsets.UTF_8);
                            // need a depth-first search to avoid triggering warning on workflows
                            // where two files legitimately import the same file
                            Set<String> importsForThisPath = new HashSet<>(currentFileImports);
                            importsForThisPath.add(match);
                            checkForRecursiveHTTPImports(fileContents, importsForThisPath);
                        }
                    }
                }
            }
        }
    }

    @Override
    public VersionTypeValidation validateWorkflowSet(Set<SourceFile> sourcefiles, String primaryDescriptorFilePath, Workflow workflow) {
        return validateEntrySet(sourcefiles, primaryDescriptorFilePath, "workflow");
    }

    @Override
    public VersionTypeValidation validateToolSet(Set<SourceFile> sourcefiles, String primaryDescriptorFilePath) {
        return validateEntrySet(sourcefiles, primaryDescriptorFilePath, "tool");
    }

    @Override
    public VersionTypeValidation validateTestParameterSet(Set<SourceFile> sourceFiles) {
        return checkValidJsonAndYamlFiles(sourceFiles, DescriptorLanguage.FileType.WDL_TEST_JSON);
    }

    @Override
    public Map<String, SourceFile> processImports(String repositoryId, String content, Version version,
            SourceCodeRepoInterface sourceCodeRepoInterface, String filepath) {
        return processImports(repositoryId, content, version, sourceCodeRepoInterface, new HashMap<>(), filepath);
    }

    private Map<String, SourceFile> processImports(String repositoryId, String content, Version version,
            SourceCodeRepoInterface sourceCodeRepoInterface, Map<String, SourceFile> imports, String currentFilePath) {
        DescriptorLanguage.FileType fileType = DescriptorLanguage.FileType.DOCKSTORE_WDL;
        ParsedInformation parsedInformation = getParsedInformation(version, DescriptorLanguage.WDL);

        // Use matcher to get imports
        String[] lines = StringUtils.split(content, '\n');
        Set<String> currentFileImports = new HashSet<>();

        for (String line : lines) {
            Matcher m = IMPORT_PATTERN.matcher(line);

            while (m.find()) {
                String match = m.group(1);
                if (!match.startsWith("http://") && !match.startsWith("https://")) { // Don't resolve URLs
                    parsedInformation.setHasLocalImports(true);
                    currentFileImports.add(match.replaceFirst("file://", "")); // remove file:// from path
                } else {
                    parsedInformation.setHasHTTPImports(true);
                }
            }
        }

        for (String importPath : currentFileImports) {
            String absoluteImportPath = unsafeConvertRelativePathToAbsolutePath(currentFilePath, importPath);
            if (!imports.containsKey(absoluteImportPath)) {
                final String fileResponse = sourceCodeRepoInterface.readGitRepositoryFile(repositoryId, fileType, version, absoluteImportPath);
                if (fileResponse == null) {
                    SourceCodeRepoInterface.LOG.error("Could not read: " + absoluteImportPath);
                    continue;
                }
                SourceFile importFile = SourceFile.limitedBuilder().type(DescriptorLanguage.FileType.DOCKSTORE_WDL).content(fileResponse).path(importPath).absolutePath(absoluteImportPath).build();
                imports.put(absoluteImportPath, importFile);
                imports.putAll(processImports(repositoryId, importFile.getContent(), version, sourceCodeRepoInterface, imports, absoluteImportPath));
            }
        }
        return imports;
    }

    /**
     * This method will get the content for tool tab with descriptor type = WDL
     * It will then call another method to transform the content into JSON string and return
     *
     * @param mainDescName         the name of the main descriptor
     * @param mainDescriptor       the content of the main descriptor
     * @param secondarySourceFiles the content of the secondary descriptors in a map, looks like file paths -> content
     * @param type                 tools or DAG
     * @param dao                  used to retrieve information on tools
     * @return either a list of tools or a json map
     */
    @Override
    public Optional<String> getContent(String mainDescName, String mainDescriptor, Set<SourceFile> secondarySourceFiles,
            LanguageHandlerInterface.Type type, ToolDAO dao) {
        // Initialize general variables
        String callType = "call"; // This may change later (ex. tool, workflow)
        String toolType = "tool";
        // Initialize data structures for DAG
        Map<String, ToolInfo> toolInfoMap;
        Map<String, String> namespaceToPath;
        File tempMainDescriptor = null;
        // Write main descriptor to file
        // The use of temporary files is not needed here and might cause new problems
        try {
            tempMainDescriptor = createTempFile("main", DESCRIPTOR_SUFFIX);
            Files.asCharSink(tempMainDescriptor, StandardCharsets.UTF_8).write(mainDescriptor);

            WdlBridge wdlBridge = new WdlBridge();
            final Map<String, String> pathToContentMap = secondarySourceFiles.stream()
                    .collect(Collectors.toMap(SourceFile::getAbsolutePath, SourceFile::getContent));
            wdlBridge.setSecondaryFiles(new HashMap<>(pathToContentMap));

            // Iterate over each call, grab docker containers
            Map<String, DockerParameter> callsToDockerMap = wdlBridge.getCallsToDockerMap(tempMainDescriptor.getAbsolutePath(), mainDescName);

            // Iterate over each call, determine dependencies
            Map<String, List<String>> callsToDependencies = wdlBridge.getCallsToDependencies(tempMainDescriptor.getAbsolutePath(), mainDescName);
            toolInfoMap = mapConverterToToolInfo(callsToDockerMap, callsToDependencies);

            // Get import files
            namespaceToPath = wdlBridge.getImportMap(tempMainDescriptor.getAbsolutePath(), mainDescName);
        } catch (WdlParser.SyntaxError ex) {
            String exMsg = WDLHandler.WDL_PARSE_ERROR + ex.getMessage();
            exMsg = getUnsupportedWDLVersionErrorString(tempMainDescriptor.getAbsolutePath()).orElse(exMsg);
            LOG.error(exMsg, ex);
            throw new CustomWebApplicationException(exMsg, HttpStatus.SC_UNPROCESSABLE_ENTITY);
        } catch (IOException | NoSuchElementException ex) {
            final String exMsg = "Could not process request, " + ex.getMessage();
            LOG.error(exMsg, ex);
            throw new CustomWebApplicationException(exMsg, HttpStatus.SC_INTERNAL_SERVER_ERROR);
        } catch (StackOverflowError error) {
            throw createStackOverflowThrowable(error);
        } finally {
            FileUtils.deleteQuietly(tempMainDescriptor);
        }
        return convertMapsToContent(mainDescName, type, dao, callType, toolType, toolInfoMap, namespaceToPath);
    }

    /**
     * Convenience function to convert old map with values of Docker image names to values of DockerParameter
     *
     * @param callsToDockerMap
     * @return
     */
    protected static Map<String, DockerParameter> convertToDockerParameter(Map<String, String> callsToDockerMap) {
        return callsToDockerMap.entrySet().stream().collect(
                Collectors.toMap(Map.Entry::getKey, v -> new DockerParameter(v.getValue(), DockerImageReference.UNKNOWN), (x, y) -> y, LinkedHashMap::new));
    }

    /**
     * For existing code, converts from maps of untyped data to ToolInfo
     * @param callsToDockerMap map from names of tools to Docker containers
     * @param callsToDependencies map from names of tools to names of their parent tools (dependencies)
     * @return
     */
    protected static Map<String, ToolInfo> mapConverterToToolInfo(Map<String, DockerParameter> callsToDockerMap, Map<String, List<String>> callsToDependencies) {
        Map<String, ToolInfo> toolInfoMap;
        toolInfoMap = new HashMap<>();
        callsToDockerMap.forEach((toolName, dockerParameter) -> toolInfoMap.compute(toolName, (key, value) -> {
            if (value == null) {
                DockerSpecifier dockerSpecifier = LanguageHandlerInterface.determineImageSpecifier(dockerParameter.imageName(), dockerParameter.imageReference());
                return new ToolInfo(dockerParameter.imageName(), new ArrayList<>(), dockerSpecifier);
            } else {
                value.dockerContainer = dockerParameter.imageName();
                return value;
            }
        }));
        callsToDependencies.forEach((toolName, dependencies) -> toolInfoMap.compute(toolName, (key, value) -> {
            if (value == null) {
                return new ToolInfo(null, new ArrayList<>());
            } else {
                value.toolDependencyList.addAll(dependencies);
                return value;
            }
        }));
        return toolInfoMap;
    }

    /**
     * Convert a possibly invalid semantic version string to a valid semantic version string
     * @param semVerString semantic version string to convert
     * @return a valid semantic version string
     */
    public static String enhanceSemanticVersionString(String semVerString) {
        // according to https://semver.org/
        // A normal version number MUST take the form X.Y.Z where X, Y, and Z are
        // non-negative integers, and MUST NOT contain leading zeroes.
        // Unfortunately WDL uses an invalid version number, e.g. '1.1' without
        // a third integer so we will have to fix it so we can use semver
        // We use a regex to find out if the version string is incomplete,
        // e.g. '1.1' or '1.1-foo' and insert a '.0' after X.Y to make it X.Y.Z,
        // e.g. '1.1.0' or '1.1.0-foo'
        // We match X.Y only at the beginning of the string
        // https://stackoverflow.com/questions/45544010/regex-to-match-a-digit-not-followed-by-a-dot
        return semVerString.replaceFirst("^(\\d+\\.\\d+)(?!\\.)(.*)", "$1\\.0$2");
    }

    /**
     * Check if the input semantic version string is greater than the newest currently supported WDL semantic version string
     * @param semVerString semantic version string to compare
     * @return whether the input semantic version string is greater
     */
    public static boolean versionIsGreaterThanCurrentlySupported(String semVerString) {
        String enhancedSemVerString = enhanceSemanticVersionString(semVerString);

        com.github.zafarkhaja.semver.Version semVer;
        try {
            semVer = com.github.zafarkhaja.semver.Version.valueOf(enhancedSemVerString);
        } catch (IllegalArgumentException | UnexpectedCharacterException | LexerException | UnexpectedTokenException ex) {
            // https://github.com/zafarkhaja/jsemver#exception-handling
            // if semVer cannot parse the version string it is probably not a good version string
            // Paradoxically semver would fail to parse the valid version string 'draft-3'
            // Fortunately 'draft-3' is not a newer version than currently supported version '1.0'
            // In general return false since we cannot determine if the version is greater than Dockstore's
            // currently supported WDL version
            return false;
        }
        return semVer.greaterThan(com.github.zafarkhaja.semver.Version.valueOf(
                enhanceSemanticVersionString(LATEST_SUPPORTED_WDL_VERSION)));
    }

    /**
     * Get the version from the WDL descriptor file content. If no version is found, return the default WDL version defined by the WDL spec.
     * If the version is invalid, return Optional.empty()
     *
     * @param primaryDescriptorPath The absolute path of the descriptor SourceFile to get the 'version' from
     * @param sourceFiles           A set of SourceFiles containing the primary descriptor SourceFile and any imports
     * @return
     */
    public static Optional<String> getLanguageVersion(String primaryDescriptorPath, Set<SourceFile> sourceFiles) {
        WdlBridge wdlBridge = new WdlBridge();

        Optional<SourceFile> primaryDescriptor = sourceFiles.stream().filter(sourceFile -> sourceFile.getAbsolutePath().equals(primaryDescriptorPath)).findFirst();
        if (primaryDescriptor.isEmpty()) {
            return Optional.empty();
        }

        final String primaryDescriptorContent = primaryDescriptor.get().getContent();
        Map<String, String> secondaryFiles = new HashMap<>();
        sourceFiles.stream()
                .filter(sourceFile -> !sourceFile.getAbsolutePath().equals(primaryDescriptorPath))
                .forEach(descriptorSourceFile -> {
                    secondaryFiles.put(descriptorSourceFile.getAbsolutePath(), descriptorSourceFile.getContent());
                });
        wdlBridge.setSecondaryFiles(secondaryFiles);

        File tempMainDescriptor = null;
        try {
            tempMainDescriptor = createTempFile("main", DESCRIPTOR_SUFFIX);
            Files.asCharSink(tempMainDescriptor, StandardCharsets.UTF_8).write(primaryDescriptorContent);
            // It's possible for isVersionValid to be false for a valid 'version' so we must double-check by trying to find a 'version' string in the content
            // Ex: Cromwell doesn't support WDL 1.1 so a 'version 1.1' workflow will return false for isVersionValid
            final boolean isVersionValid = wdlBridge.isVersionFieldValid(tempMainDescriptor.getAbsolutePath(), primaryDescriptorPath);
            Optional<String> parsedVersionString = getSemanticVersionString(primaryDescriptorContent);

            if (parsedVersionString.isEmpty()) {
                if (isVersionValid) {
                    // Return default version if there's no parsed 'version' field and the version is valid (no 'version' is valid)
                    return Optional.of(DEFAULT_WDL_VERSION);
                }
                // If there's no parsed 'version' string and the version isn't valid, then there's likely something wrong with the version
                return Optional.empty();
            }
            return parsedVersionString;
        } catch (IOException e) {
            LOG.error("Error creating temporary file for descriptor {}", primaryDescriptorPath, e);
            throw new CustomWebApplicationException(e.getMessage(), HttpStatus.SC_INTERNAL_SERVER_ERROR);
        } catch (StackOverflowError error) {
            throw createStackOverflowThrowable(error);
        } finally {
            // Delete the temp directory and its contents
            FileUtils.deleteQuietly(tempMainDescriptor);
        }
    }

    /**
     * Get the semantic version string from the WDL file
     * @param descriptorContent the file content of the primary WDL descriptor
     * @return the semantic version string, e.g. '1.0', which should be in the first code line, e.g. 'version 1.0' or 'draft-3'
     */
    public static Optional<String> getSemanticVersionString(String descriptorContent) {
        WdlBridge wdlBridge = new WdlBridge();
        Optional<String> firstCodeLine = wdlBridge.getFirstCodeLine(descriptorContent);

        // https://www.scala-lang.org/files/archive/api/2.13.x/scala/jdk/javaapi/OptionConverters$.html
        // The WDL specification says that WDL descriptors from now on must have
        // a version string as the first line, e.g. 'version 1.0' or 'version draft-3'
        // https://github.com/openwdl/wdl/blob/main/versions/1.0/SPEC.md#versioning
        // however some very old WDL scripts may not have a version string line
        // Check to see if we found the first line of code and that it has two parts
        if (firstCodeLine.isPresent()) {
            String wdlCommentSymbol = "#";
            String semanticVersionLine = firstCodeLine.get();
            // Remove comment in lines like 'version 1.0 # this is a comment'
            String semanticVersionStringWithoutComments = semanticVersionLine.split(wdlCommentSymbol)[0];
            String[] semanticVersionStringArray = semanticVersionStringWithoutComments.split("\\s+");
            // If there is a version string line the first part should be 'version'
            if ("version".equals(semanticVersionStringArray[0]) && semanticVersionStringArray.length == 2) {
                // Return the version such as '1.0' or 'draft-3'
                // Note: if the programmer made a mistake this could be some
                // bogus string but we will have semver check it later
                return Optional.of(semanticVersionStringArray[1]);
            }
        }
        // otherwise return nothing
        return Optional.empty();
    }

    public static Optional<String> getUnsupportedWDLVersionErrorString(String primaryDescriptorContent) {
        Optional<String> semVersionString = getSemanticVersionString(primaryDescriptorContent);
        if (semVersionString.isPresent() && versionIsGreaterThanCurrentlySupported(semVersionString.get())) {
            return Optional.of("Dockstore only supports up to  WDL version " + LATEST_SUPPORTED_WDL_VERSION + ". The version of"
                    + " this workflow is " + semVersionString.get() + ". Dockstore cannot verify or parse this WDL version.");
        } else {
            return Optional.empty();
        }
    }

    /**
     * Returns a map of file input names to types. For example:
     * <pre>
     * "checkerWorkflow.input_crai_files" -> "Array[File]?"
     * "checkerWorkflow.referenceFilesBlob" -> "File"
     * "checkerWorkflow.input_cram_files" -> "Array[File]"
     * "checkerWorkflow.inputTruthVCFFile" -> "File"
     * "checkerWorkflow.variantcaller.referenceGenomeFilesTarGz" -> "Array[File]?"
     * </pre>
     */
    Optional<Map<String, String>> getFileInputs(String primaryDescriptorContent, Set<SourceFile> sourceFiles) {
        final WdlBridge wdlBridge = new WdlBridge();
        wdlBridge.setSecondaryFiles(sourceFiles.stream().collect(Collectors.toMap(SourceFile::getAbsolutePath, SourceFile::getContent)));
        File tempMainDescriptor = null;
        try {
            tempMainDescriptor = createTempFile("main", DESCRIPTOR_SUFFIX);
            Files.asCharSink(tempMainDescriptor, StandardCharsets.UTF_8).write(primaryDescriptorContent);
            return Optional.of(wdlBridge.getInputFiles(tempMainDescriptor.getPath()));
        } catch (StackOverflowError | SyntaxError | IOException e) { // StackOverflowError: https://github.com/dockstore/dockstore/issues/5496
            LOG.error("Error parsing WDL", e);
            return Optional.empty();
        } finally {
            FileUtils.deleteQuietly(tempMainDescriptor); // Works with null parameter
        }
    }

    @Override
    public Optional<Boolean> isOpenData(final WorkflowVersion workflowVersion, final CheckUrlInterface checkUrlInterface) {
        final Optional<SourceFile> maybeDescriptor = workflowVersion.findPrimaryDescriptor();
        if (maybeDescriptor.isEmpty()) {
            return Optional.empty();
        }
        final SourceFile primaryDescriptor = maybeDescriptor.get();
        final Optional<Map<String, String>> fileInputMap =
            getFileInputs(primaryDescriptor.getContent(), workflowVersion.getSourceFiles());
        if (fileInputMap.isEmpty()) { // Primary descriptor does not exist or is not well-formed
            return Optional.empty();
        }
        if (fileInputMap.get().isEmpty()) {
            // Has no file inputs, consider it open.
            // https://github.com/dockstore/dockstore/pull/5347#discussion_r1117213315
            return Optional.of(true);
        }
        final List<JSONObject> testFiles = workflowVersion.findTestFiles().stream().map(SourceFileHelper::testFileAsJsonObject)
            .filter(Optional::isPresent).map(Optional::get).toList();
        if (testFiles.isEmpty()) {
            // Has file inputs, but no test parameter files; not open data.
            return Optional.of(false);
        }

        final UrlStatus urlStatus =
            anyTestFileHasAllOpenData(fileInputMap.get(), testFiles, checkUrlInterface);
        if (urlStatus == UrlStatus.UNKNOWN) {
            return Optional.empty();
        } else {
            return Optional.of(urlStatus == UrlStatus.ALL_OPEN);
        }
    }

    private UrlStatus anyTestFileHasAllOpenData(final Map<String, String> fileInputMap,
        final List<JSONObject> testFiles, CheckUrlInterface checkUrlInterface) {
        for (JSONObject testFile: testFiles) {
            final List<FileInputs> testFileInputs = fileInputValues(testFile, fileInputMap);
            if (everyFileInputHasValue(testFileInputs)) {
                final Set<String> possibleUrls =
                    testFileInputs.stream().map(p -> p.values()).flatMap(Set::stream).collect(
                        Collectors.toSet());
                final UrlStatus urlStatus = checkUrlInterface.checkUrls(possibleUrls);
                if (urlStatus == UrlStatus.ALL_OPEN || urlStatus == UrlStatus.UNKNOWN) {
                    return urlStatus;
                }
            }
        }
        return UrlStatus.NOT_ALL_OPEN;
    }

    private static boolean everyFileInputHasValue(final List<FileInputs> fileInputs) {
        return fileInputs.stream().noneMatch(fileInput -> fileInput.values.isEmpty());
    }

    /**
     * Given the known input parameter names of type file/array[file], look for the corresponding
     * values in the test parameter file. Cannot assume the test parameter file structure matches
     * what the WDL is expecting; they could be out of sync.
     *
     * @param json
     * @param fileInputs
     * @return
     */
    List<FileInputs> fileInputValues(JSONObject json, Map<String, String> fileInputs) {
        return fileInputs.entrySet().stream().map(entry -> {
            final String parameterName = entry.getKey();
            final String parameterType = entry.getValue();
            return new FileInputs(parameterName, parameterType, fileInputValues(json, parameterName, parameterType));
        }).toList();
    }

    private Set<String> fileInputValues(JSONObject json, String parameterName, String parameterType) {
        if (json.has(parameterName)) {
            final Object fileParameterValue = json.get(parameterName);
            if (parameterType.toLowerCase().contains("array")) { // Declared in WDL as an array of files
                return getStringsFromMaybeArray(fileParameterValue);
            } else if (fileParameterValue instanceof String maybeUrl) { // Declared in WDL as a single File
                return Set.of(maybeUrl);
            }
        }
        return Set.of();
    }

    /**
     * The input parameter is defined as an array of files in WDL; if the parameter is also an array
     * in the test parameter file, return all string elements of the array.
     * @param parameterValue
     * @return
     */
    private Set<String> getStringsFromMaybeArray(Object parameterValue) {
        final Set<String> values = new HashSet<>();
        if (parameterValue instanceof JSONArray jsonArray) {
            for (Object element : jsonArray) {
                if (element instanceof String maybeUrl) {
                    values.add(maybeUrl);
                }
            }
        }
        return values;
    }


    private static RuntimeException createStackOverflowThrowable(StackOverflowError error) {
        throw new CustomWebApplicationException(ERROR_PARSING_WORKFLOW_YOU_MAY_HAVE_A_RECURSIVE_IMPORT, HttpStatus.SC_UNPROCESSABLE_ENTITY);
    }

    /**
     * Securely creates a temporary file, using pattern recommended by SonarCloud
     *
     * @param suffix
     * @param prefix
     * @return a temporary file
     * @throws IOException
     */
    private static File createTempFile(String prefix, String suffix) throws IOException {
        if (SystemUtils.IS_OS_UNIX) {
            FileAttribute<Set<PosixFilePermission>> attr = asFileAttribute(fromString("rwx------"));
            return java.nio.file.Files.createTempFile(prefix, suffix, attr).toFile();
        } else {
            File f = java.nio.file.Files.createTempFile(prefix, suffix).toFile(); //NOSONAR - Next line addresses the security issue by limiting file permissions to this user
            if (!f.setReadable(true, true) || !f.setWritable(true, true)
                || !f.setExecutable(true, true)) {
                throw new IOException("Error limiting permissions for temporary file");
            }
            return f;
        }
    }
    record FileInputs(String name, String type, Set<String> values) {}

}
