/**
 * Copyright 2015 Cloudera Inc.
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

package org.kitesdk.data.spi.filesystem;

import java.io.IOException;
import java.net.URI;

import org.apache.avro.generic.GenericData.Record;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.junit.Assert;
import org.junit.Test;
import org.kitesdk.data.Dataset;
import org.kitesdk.data.TestDatasetRepositories;
import org.kitesdk.data.spi.DatasetRepository;
import org.kitesdk.data.spi.MetadataProvider;

public class TestTemporaryFileSystemDatasetRepository
    extends TestDatasetRepositories {

  public TestTemporaryFileSystemDatasetRepository(boolean distributed) {
    super(distributed);
  }

  public static String KEY = "key";

  @Test
  public void verifyIfRequiredTmpFileSystemDatasetRepositoryHasBeenCreated()
      throws IOException {

    Dataset<Record> created = repo.create(NAMESPACE, NAME, testDescriptor);
    URI location = created.getDescriptor().getLocation();
    System.err.println(location);
    Assert.assertNotNull(
        "FileSystemDatasetRepository should return descriptor locations",
        location);
    Assert.assertTrue("Dataset data directory:" + location + " should exist",
        fileSystem.exists(new Path(location)));

  }

  @Override
  public DatasetRepository newRepo(MetadataProvider provider) {
    return new TemporaryFileSystemDatasetRepository(new Configuration(),
        testDirectory, NAMESPACE, KEY);
  }
}
