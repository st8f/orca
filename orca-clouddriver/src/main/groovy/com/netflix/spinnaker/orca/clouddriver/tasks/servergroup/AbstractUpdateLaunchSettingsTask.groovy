/*
 * Copyright 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.clouddriver.tasks.servergroup

import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus
import com.netflix.spinnaker.orca.api.pipeline.Task
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution
import com.netflix.spinnaker.orca.api.pipeline.TaskResult
import com.netflix.spinnaker.orca.clouddriver.KatoService
import com.netflix.spinnaker.orca.clouddriver.model.TaskId
import com.netflix.spinnaker.orca.clouddriver.utils.CloudProviderAware
import com.netflix.spinnaker.orca.kato.tasks.DeploymentDetailsAware
import groovy.util.logging.Slf4j
import javax.annotation.Nonnull

@Slf4j
abstract class AbstractUpdateLaunchSettingsTask implements Task, DeploymentDetailsAware, CloudProviderAware {
  KatoService kato
  String defaultBakeAccount
  AbstractUpdateLaunchSettingsTask(KatoService kato, String defaultBakeAccount) {
    this.kato = kato
    this.defaultBakeAccount = defaultBakeAccount
  }

  @Nonnull
  @Override
  TaskResult execute(@Nonnull StageExecution stage) {
    def ops
    def cloudProvider = getCloudProvider(stage)
    if (cloudProvider == "aws") {
      // Since AWS is the only provider that needs to do anything here, it felt like overkill to do the
      // provider-specific rigmarole here.
      ops = getAwsOps(stage)
    } else if (cloudProvider == "yandex") {
      ops = getYandexOps(stage)
    } else {
      ops = [[(getOperation()): stage.context]]
    }

    def taskId = kato.requestOperations(cloudProvider, ops)

    return TaskResult.builder(ExecutionStatus.SUCCEEDED)
      .context(getContext(stage, taskId))
      .build()
  }

  private getAwsOps(StageExecution stage) {
    def operation = new HashMap(stage.context)
    operation.amiName = getImage(stage)
    operation.asgName = operation.asgName ?: operation.serverGroupName

    def ops = []
    if (stage.context.credentials != defaultBakeAccount) {
      ops << [allowLaunchDescription: convertAllowLaunch(getCredentials(stage),
                                                         defaultBakeAccount,
                                                         stage.context.region as String,
                                                         operation.amiName as String)]
      log.info("Generated `allowLaunchDescription` (allowLaunchDescription: ${ops})")
    }

    ops << [(getOperation()): operation]
    ops
  }


  private getYandexOps(StageExecution stage) {
    def operation = new HashMap(stage.context)
    operation.name = operation.asgName ?: operation.serverGroupName

    operation.instanceTemplate.bootDiskSpec.diskSpec.imageId = operation.instanceTemplate.bootDiskSpec.diskSpec.imageId ?: getYandexImageId(stage)
    if (!operation.instanceTemplate.bootDiskSpec.diskSpec.imageId) {
      throw new IllegalStateException("No image could be found in ${stage.context.region}.")
    }

    def ops = []
    ops << [(getOperation()): operation]
    ops
  }

  private String getYandexImageId(StageExecution stage) {
    String imageId

    withImageFromPrecedingStage(stage, null, "yandex") {
      imageId = imageId ?: it.imageId
    }

    withImageFromDeploymentDetails(stage, null, "yandex") {
      imageId = imageId ?: it.imageId
    }

    return imageId
  }

  private String getImage(StageExecution stage) {
    String amiName = stage.context.amiName
    String targetRegion = stage.context.region
    withImageFromPrecedingStage(stage, targetRegion, "aws") {
      amiName = amiName ?: it.amiName
    }

    withImageFromDeploymentDetails(stage, targetRegion, "aws") {
      amiName = amiName ?: it.amiName
    }
    return amiName
  }

  private static Map convertAllowLaunch(String targetAccount, String sourceAccount, String region, String ami) {
    [account: targetAccount, credentials: sourceAccount, region: region, amiName: ami]
  }

  abstract String getOperation()
  abstract Map<String, Object> getContext(StageExecution stage, TaskId taskId)
}
