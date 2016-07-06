package com.gilt.aws.lambda

import sbt._

import scala.util.{Failure, Success}

object AwsLambdaPlugin extends AutoPlugin {

  object autoImport {
    val createLambda = taskKey[Map[String, LambdaARN]]("Create a new AWS Lambda function from the current project")
    val updateLambda = taskKey[Map[String, LambdaARN]]("Package and deploy the current project to an existing AWS Lambda")
    val addPermissionLambda = taskKey[Map[String, LambdaARN]]("Add permission to the current project to an existing AWS Lambda")

    val s3Bucket = settingKey[Option[String]]("ID of the S3 bucket where the jar will be uploaded")
    val lambdaName = settingKey[Option[String]]("Name of the AWS Lambda to update")
    val handlerName = settingKey[Option[String]]("Name of the handler to be executed by AWS Lambda")
    val roleArn = settingKey[Option[String]]("ARN of the IAM role for the Lambda function")
    val region = settingKey[Option[String]]("Name of the AWS region to connect to")
    val awsLambdaTimeout = settingKey[Option[Int]]("The Lambda timeout length in seconds (1-300)")
    val awsLambdaMemory = settingKey[Option[Int]]("The amount of memory in MB for the Lambda function (128-1536, multiple of 64)")
    val lambdaHandlers = settingKey[Seq[(String, String)]]("A sequence of pairs of Lambda function names to handlers (for multiple handlers in one jar)")
  }

  import autoImport._

  override def requires = sbtassembly.AssemblyPlugin

  override lazy val projectSettings = Seq(
    addPermissionLambda := doAddPermissionLambda(
      region = region.value,
      lambdaName = lambdaName.value
    ),
    updateLambda := doUpdateLambda(
      region = region.value,
      jar = sbtassembly.AssemblyKeys.assembly.value,
      s3Bucket = s3Bucket.value,
      lambdaName = lambdaName.value,
      handlerName = handlerName.value,
      lambdaHandlers = lambdaHandlers.value
    ),
    createLambda := doCreateLambda(
      region = region.value,
      jar = sbtassembly.AssemblyKeys.assembly.value,
      s3Bucket = s3Bucket.value,
      lambdaName = lambdaName.value,
      handlerName = handlerName.value,
      lambdaHandlers = lambdaHandlers.value,
      roleArn = roleArn.value,
      timeout = awsLambdaTimeout.value,
      memory = awsLambdaMemory.value
    ),
    s3Bucket := None,
    lambdaName := Some(sbt.Keys.name.value),
    handlerName := None,
    lambdaHandlers := List.empty[(String, String)],
    roleArn := None,
    region := Some("us-east-1"),
    awsLambdaMemory := None,
    awsLambdaTimeout := None
  )

  private def doAddPermissionLambda(region: Option[String], lambdaName: Option[String]): Map[String, LambdaARN] = {
    val resolvedRegion = resolveRegion(region)

    (for {
      l <- lambdaName
      resolvedLambdaName = LambdaName(l)
    } yield {
      AwsLambda.addPermissionLambda(resolvedRegion, resolvedLambdaName) match {
        case Success(addPermissionResult) =>
          resolvedLambdaName.value -> LambdaARN(addPermissionResult.getStatement)
        case Failure(exception) =>
          sys.error(s"Error add permission lambda: ${exception.getMessage} ${exception.getStackTraceString}")
      }
    }).toMap
  }

  private def doUpdateLambda(region: Option[String], jar: File, s3Bucket: Option[String], lambdaName: Option[String], 
      handlerName: Option[String], lambdaHandlers: Seq[(String, String)]): Map[String, LambdaARN] = {
    val resolvedRegion = resolveRegion(region)
    val resolvedBucketId = resolveBucketId(s3Bucket)
    val resolvedLambdaHandlers = resolveLambdaHandlers(lambdaName, handlerName, lambdaHandlers)

    AwsS3.pushJarToS3(jar, resolvedBucketId) match {
      case Success(s3Key) => (for (resolvedLambdaName <- resolvedLambdaHandlers.keys) yield {
        AwsLambda.updateLambda(resolvedRegion, resolvedLambdaName, resolvedBucketId, s3Key) match {
          case Success(updateFunctionCodeResult) =>
            resolvedLambdaName.value -> LambdaARN(updateFunctionCodeResult.getFunctionArn)
          case Failure(exception) =>
            sys.error(s"Error updating lambda: ${exception.getStackTraceString}")
        }
      }).toMap
      case Failure(exception) =>
        sys.error(s"Error uploading jar to S3 lambda: ${exception.getStackTraceString}")
    }
  }

  private def doCreateLambda(region: Option[String], jar: File, s3Bucket: Option[String], lambdaName: Option[String], 
      handlerName: Option[String], lambdaHandlers: Seq[(String, String)], roleArn: Option[String], timeout: Option[Int], memory: Option[Int]): Map[String, LambdaARN] = {
    val resolvedRegion = resolveRegion(region)
    val resolvedLambdaHandlers = resolveLambdaHandlers(lambdaName, handlerName, lambdaHandlers)
    val resolvedRoleName = resolveRoleARN(roleArn)
    val resolvedBucketId = resolveBucketId(s3Bucket)
    val resolvedTimeout = resolveTimeout(timeout)
    val resolvedMemory = resolveMemory(memory)

    AwsS3.pushJarToS3(jar, resolvedBucketId) match {
      case Success(s3Key) =>
        for ((resolvedLambdaName, resolvedHandlerName) <- resolvedLambdaHandlers) yield {
          AwsLambda.createLambda(resolvedRegion, jar, resolvedLambdaName, resolvedHandlerName, resolvedRoleName, resolvedBucketId, resolvedTimeout, resolvedMemory) match {
            case Success(createFunctionCodeResult) =>
              resolvedLambdaName.value -> LambdaARN(createFunctionCodeResult.getFunctionArn)
            case Failure(exception) =>
              sys.error(s"Failed to create lambda function: ${exception.getLocalizedMessage}\n${exception.getStackTraceString}")
          }
        }
      case Failure(exception) =>
        sys.error(s"Error upload jar to S3 lambda: ${exception.getLocalizedMessage}")
    }
  }

  private def resolveRegion(sbtSettingValueOpt: Option[String]): Region = {
    sbtSettingValueOpt match {
      case Some(regionSetting) => Region(regionSetting)
      case None => sys.env.get(EnvironmentVariables.region) match {
        case Some(envVarRegion) => Region(envVarRegion)
        case None => promptUserForRegion()
      }
    }
  }

  private def resolveBucketId(sbtSettingValueOpt: Option[String]): S3BucketId = {
    sbtSettingValueOpt match {
      case Some(id) => S3BucketId(id)
      case None => sys.env.get(EnvironmentVariables.bucketId) match {
        case Some(envVarId) => S3BucketId(envVarId)
        case None => promptUserForS3BucketId()
      }
    }
  }

  private def resolveLambdaHandlers(lambdaName: Option[String], handlerName: Option[String], 
      lambdaHandlers: Seq[(String, String)]): Map[LambdaName, HandlerName] =
    if (lambdaHandlers.nonEmpty) lambdaHandlers.map { case (l, h) => LambdaName(l) -> HandlerName(h)}.toMap else {
      val l = lambdaName.getOrElse(sys.env.getOrElse(EnvironmentVariables.lambdaName, promptUserForFunctionName()))
      val h = handlerName.getOrElse(sys.env.getOrElse(EnvironmentVariables.handlerName, promptUserForHandlerName()))
      Map(LambdaName(l) -> HandlerName(h))
    }

  private def resolveRoleARN(sbtSettingValueOpt: Option[String]): RoleARN = {
    sbtSettingValueOpt match {
      case Some(f) => RoleARN(f)
      case None => sys.env.get(EnvironmentVariables.roleArn) match {
        case Some(envVarFunctionName) => RoleARN(envVarFunctionName)
        case None => promptUserForRoleARN()
      }
    }
  }

  private def resolveTimeout(sbtSettingValueOpt: Option[Int]): Option[Timeout] = {
    sbtSettingValueOpt match {
      case Some(f) => Some(Timeout(f))
      case None => sys.env.get(EnvironmentVariables.timeout).map(t => Timeout(t.toInt))
    }
  }

  private def resolveMemory(sbtSettingValueOpt: Option[Int]): Option[Memory] = {
    sbtSettingValueOpt match {
      case Some(f) => Some(Memory(f))
      case None => sys.env.get(EnvironmentVariables.memory).map(m => Memory(m.toInt))
    }
  }

  private def promptUserForRegion(): Region = {
    val inputValue = readInput(s"Enter the name of the AWS region to connect to. (You also could have set the environment variable: ${EnvironmentVariables.region} or the sbt setting: region)")

    Region(inputValue)
  }

  private def promptUserForS3BucketId(): S3BucketId = {
    val inputValue = readInput(s"Enter the AWS S3 bucket where the lambda jar will be stored. (You also could have set the environment variable: ${EnvironmentVariables.bucketId} or the sbt setting: s3Bucket)")
    val bucketId = S3BucketId(inputValue)

    AwsS3.getBucket(bucketId) match {
      case Some(_) =>
        bucketId
      case None =>
        val createBucket = readInput(s"Bucket $inputValue does not exist. Create it now? (y/n)")

        if(createBucket == "y") {
          AwsS3.createBucket(bucketId) match {
            case Success(createdBucketId) =>
              createdBucketId
            case Failure(th) =>
              println(s"Failed to create S3 bucket: ${th.getLocalizedMessage}")
              promptUserForS3BucketId()
          }
        }
        else promptUserForS3BucketId()
    }
  }

  private def promptUserForFunctionName(): String =
    readInput(s"Enter the name of the AWS Lambda. (You also could have set the environment variable: ${EnvironmentVariables.lambdaName} or the sbt setting: lambdaName)")

  private def promptUserForHandlerName(): String =
    readInput(s"Enter the name of the AWS Lambda handler. (You also could have set the environment variable: ${EnvironmentVariables.handlerName} or the sbt setting: handlerName)")

  private def promptUserForRoleARN(): RoleARN = {
    AwsIAM.basicLambdaRole() match {
      case Some(basicRole) =>
        val reuseBasicRole = readInput(s"IAM role '${AwsIAM.BasicLambdaRoleName}' already exists. Reuse this role? (y/n)")
        
        if(reuseBasicRole == "y") RoleARN(basicRole.getArn)
        else readRoleARN()
      case None =>
        val createDefaultRole = readInput(s"Default IAM role for AWS Lambda has not been created yet. Create this role now? (y/n)")
        
        if(createDefaultRole == "y") {
          AwsIAM.createBasicLambdaRole() match {
            case Success(createdRole) =>
              createdRole
            case Failure(th) =>
              println(s"Failed to create role: ${th.getLocalizedMessage}")
              promptUserForRoleARN()
          }
        } else readRoleARN()
    }
  }

  private def readRoleARN(): RoleARN = {
    val inputValue = readInput(s"Enter the ARN of the IAM role for the Lambda. (You also could have set the environment variable: ${EnvironmentVariables.roleArn} or the sbt setting: roleArn)")
    RoleARN(inputValue)
  }

  private def readInput(prompt: String): String = {
    SimpleReader.readLine(s"$prompt\n") match {
      case Some(f) =>
        f
      case None =>
        val badInputMessage = "Unable to read input"

        val updatedPrompt = if(prompt.startsWith(badInputMessage)) prompt else s"$badInputMessage\n$prompt"

        readInput(updatedPrompt)
    }
  }
}
