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

package com.cloud.vm.backup;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.cloud.utils.component.ManagerBase;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore;
import org.springframework.stereotype.Component;
import org.apache.cloudstack.api.command.user.backup.ListBackupCmd;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;

import java.util.List;
import java.util.ArrayList;

import javax.inject.Inject;

@Component
public class BackupManagerImpl extends ManagerBase implements BackupService {

    @Inject
    DataStoreManager _dataStoreMgr;

    @Override
    public List<S3ObjectSummary> listBackups(ListBackupCmd cmd) {
        List<PrimaryDataStore> stores =_dataStoreMgr.listPrimaryDataStores();
        String accessKey = "accessKey1";
        String secretKey = "verySecretKey1";
        AWSCredentials credentials = new BasicAWSCredentials(accessKey, secretKey);

        ClientConfiguration clientConfig = new ClientConfiguration();
        clientConfig.setProtocol(Protocol.HTTPS);

        AmazonS3 conn = new AmazonS3Client(credentials, clientConfig);
        conn.setEndpoint("sr03.cs.ewerk.com");

        String prefix = "hci-cl01-nhjj";

        String delimiter = "/";
        if (!prefix.endsWith(delimiter)) {
          prefix += delimiter;
        }

        ListObjectsRequest listObjectsRequest = new ListObjectsRequest()
            .withBucketName("zenko").withPrefix(prefix)
            .withDelimiter(delimiter);

        ObjectListing objects = conn.listObjects(listObjectsRequest);
        List<S3ObjectSummary> myList = new ArrayList<S3ObjectSummary>();

        myList.addAll(objects.getObjectSummaries());

        return myList;
    }
}
