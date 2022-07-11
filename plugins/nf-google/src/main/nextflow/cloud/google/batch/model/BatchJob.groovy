/*
 * Copyright 2022, Google Inc.
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

package nextflow.cloud.google.batch.model


import groovy.transform.CompileStatic
import groovy.transform.ToString
import nextflow.cloud.google.batch.json.JsonHelper
/**
 * Model a Google Batch task submit request
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
@ToString(includeNames = true, ignoreNulls = true, includePackage = false)
class BatchJob {

    // Job name.
    // It must have the format of "projects/*/locations/*/jobs/*".
    // For example: "projects/123456/locations/us-west1/jobs/job01".
    String name

    // Output only. A system generated unique ID (in UUID4 format) for the Job.
    String uid

    // Priority of the Job.
    // The valid value range is [0, 100).
    // A job with higher priority value will be scheduled to run earlier.
    Integer priority

    // Required. TaskGroups in the Job. Only one TaskGroup is supported now.
    List<TaskGroup> taskGroups = []

    // Compute resource allocation for all TaskGroups in the Job.
    AllocationPolicy allocationPolicy

    // Labels for the Job. Labels could be user provided or system generated.
    // For example,
    // "labels": {
    //    "department": "finance",
    //    "environment": "test"
    //  }
    // You can assign up to 64 labels.  [Google Compute Engine label
    // restrictions](https://cloud.google.com/compute/docs/labeling-resources#restrictions)
    // apply.
    // Label names that start with "goog-" or "google-" are reserved.
    Map<String,String> labels

    // Output only. Job status. It is read only for users.
    JobStatus status

    LogsPolicy logsPolicy = LogsPolicy.CLOUD_LOGGING

    List<JobNotification> notifications

    BatchJob addTaskGroup(TaskGroup it) {
        taskGroups.add(it)
        return this
    }

    BatchJob withAllocationPolicy(AllocationPolicy allocationPolicy) {
        this.allocationPolicy = allocationPolicy
        return this
    }

    String toJson(boolean pretty=false) {
        JsonHelper.toJson(this,pretty)
    }

    static BatchJob create(Map req) {
        assert req.imageUri
        assert req.command
        assert req.command instanceof List

        final container = new TaskContainer()
                .withImageUri(req.imageUri as String)
                .withEntrypoint(req.entrypoint as String ?: '/bin/bash')
                .withCommands(req.command as List)

        final taskSpec = new TaskSpec().addRunnable( new TaskRunnable().withContainer(container) )
        final taskGroup = new TaskGroup().withTaskSpec(taskSpec)

        if( req.volumes instanceof Map ) {
            final volumes = (Map)req.volumes
            final contVols = []
            final opts = ["-o rw,allow_other", "-implicit-dirs"]
            for( String remote : volumes.keySet() ) {
                final target = volumes.get(remote)
                contVols << "$target:$target:rw"
                taskSpec.withVolumes( [new TaskVolume(gcs: [remotePath: remote], mountPath: "/mnt/$remote".toString(), mountOptions: opts)] )
            }

            container.withVolumes( contVols )
        }

        return new BatchJob().addTaskGroup(taskGroup)
    }

}