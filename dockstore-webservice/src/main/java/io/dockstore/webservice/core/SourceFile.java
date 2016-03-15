/*
 *    Copyright 2016 OICR
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

package io.dockstore.webservice.core;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * This describes a cached copy of a remotely accessible file. Implementation specific.
 * 
 * @author xliu
 */
@ApiModel("SourceFile")
@Entity
@Table(name = "sourcefile")
public class SourceFile {
    public enum FileType {
        // Add supported descriptor types here
        DOCKSTORE_CWL, DOCKSTORE_WDL, DOCKERFILE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ApiModelProperty("Implementation specific ID for the source file in this web service")
    private long id;

    @Enumerated(EnumType.STRING)
    @ApiModelProperty(value = "Enumerates the type of file", required = true)
    private FileType type;

    @Column(columnDefinition = "TEXT")
    @ApiModelProperty("Cache for the contents of the target file")
    private String content;

    public void update(SourceFile file) {
        content = file.content;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public FileType getType() {
        return type;
    }

    public void setType(FileType type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
