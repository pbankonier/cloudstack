// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.api.command.user.backup;

import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.cloud.vm.backup.Backup;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.BaseListTaggedResourcesCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.BackupResponse;

import java.util.ArrayList;
import java.util.List;

@APICommand(name = "listBackups", description = "List backups by conditions", responseObject = BackupResponse.class, since = "4.2.0", entityType = {Backup.class},
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class ListBackupCmd extends BaseListTaggedResourcesCmd {

    private static final String s_name = "listbackupsresponse";

    @Override
    public void execute() {
        List<S3ObjectSummary> result = _BackupService.listBackups(this);
        ListResponse<BackupResponse> response = new ListResponse<BackupResponse>();
        List<BackupResponse> backupResponses = new ArrayList<BackupResponse>();

        for (S3ObjectSummary r : result) {
          BackupResponse backupResponse = _responseGenerator.createBackupResponse(r);
          backupResponse.setObjectName("backup");
          backupResponses.add(backupResponse);
        }

        response.setResponses(backupResponses);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }

    @Override
    public String getCommandName() {
        return s_name;
    }

}
