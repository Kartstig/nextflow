/*
 * Copyright 2013-2023, Seqera Labs
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
 */

/*
 * task_execution.proto
 * No description provided (generated by Swagger Codegen https://github.com/swagger-api/swagger-codegen)
 *
 * OpenAPI spec version: version not set
 * 
 *
 * NOTE: This class is auto generated by the swagger code generator program.
 * https://github.com/swagger-api/swagger-codegen.git
 * Do not edit the class manually.
 */


package nextflow.ga4gh.tes.client.model;

import java.util.Objects;

import com.google.gson.annotations.SerializedName;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * OutputFileLog describes a single output file. This describes file details after the task has completed successfully, for logging purposes.
 */
@ApiModel(description = "OutputFileLog describes a single output file. This describes file details after the task has completed successfully, for logging purposes.")
@javax.annotation.Generated(value = "io.swagger.codegen.languages.JavaClientCodegen", date = "2018-02-01T15:43:49.638Z")
public class TesOutputFileLog {
  @SerializedName("url")
  private String url = null;

  @SerializedName("path")
  private String path = null;

  @SerializedName("size_bytes")
  private String sizeBytes = null;

  public TesOutputFileLog url(String url) {
    this.url = url;
    return this;
  }

   /**
   * URL of the file in storage, e.g. s3://bucket/file.txt
   * @return url
  **/
  @ApiModelProperty(value = "URL of the file in storage, e.g. s3://bucket/file.txt")
  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public TesOutputFileLog path(String path) {
    this.path = path;
    return this;
  }

   /**
   * Path of the file inside the container. Must be an absolute path.
   * @return path
  **/
  @ApiModelProperty(value = "Path of the file inside the container. Must be an absolute path.")
  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public TesOutputFileLog sizeBytes(String sizeBytes) {
    this.sizeBytes = sizeBytes;
    return this;
  }

   /**
   * Size of the file in bytes.
   * @return sizeBytes
  **/
  @ApiModelProperty(value = "Size of the file in bytes.")
  public String getSizeBytes() {
    return sizeBytes;
  }

  public void setSizeBytes(String sizeBytes) {
    this.sizeBytes = sizeBytes;
  }


  @Override
  public boolean equals(java.lang.Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TesOutputFileLog tesOutputFileLog = (TesOutputFileLog) o;
    return Objects.equals(this.url, tesOutputFileLog.url) &&
        Objects.equals(this.path, tesOutputFileLog.path) &&
        Objects.equals(this.sizeBytes, tesOutputFileLog.sizeBytes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(url, path, sizeBytes);
  }


  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TesOutputFileLog {\n");
    
    sb.append("    url: ").append(toIndentedString(url)).append("\n");
    sb.append("    path: ").append(toIndentedString(path)).append("\n");
    sb.append("    sizeBytes: ").append(toIndentedString(sizeBytes)).append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
   * Convert the given object to string with each line indented by 4 spaces
   * (except the first line).
   */
  private String toIndentedString(java.lang.Object o) {
    if (o == null) {
      return "null";
    }
    return o.toString().replace("\n", "\n    ");
  }
  
}

