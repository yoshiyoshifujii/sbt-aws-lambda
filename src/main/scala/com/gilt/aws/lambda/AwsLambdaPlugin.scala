package com.gilt.aws.lambda

import java.net.URLEncoder

import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClient
import com.amazonaws.services.identitymanagement.model.{Role, CreateRoleRequest}
import com.amazonaws.services.lambda.AWSLambdaClient
import com.amazonaws.services.lambda.model._
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{CannedAccessControlList, PutObjectRequest}
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import sbt._

object AwsLambdaPlugin extends AutoPlugin {

  object EnvironmentVariables {
    val bucketId = "AWS_LAMBDA_BUCKET_ID"
    val lambdaName = "AWS_LAMBDA_NAME"
    val handlerName = "AWS_LAMBDA_HANDLER_NAME"
    val roleArn = "AWS_LAMBDA_IAM_ROLE_ARN"
  }

  object autoImport {
    val createLambda = taskKey[Unit]("Create a new AWS Lambda function from the current project")
    val updateLambda = taskKey[Unit]("Package and deploy the current project to an existing AWS Lambda")

    val s3Bucket = settingKey[Option[String]]("ID of the S3 bucket where the jar will be uploaded")
    val lambdaName = settingKey[Option[String]]("Name of the AWS Lambda to update")
    val handlerName = settingKey[Option[String]]("Name of the handler to be executed by AWS Lambda")
    val roleArn = settingKey[Option[String]]("ARN of the IAM role for the Lambda function")
  }

  import autoImport._

  override def requires = sbtassembly.AssemblyPlugin

  override lazy val projectSettings = Seq(
    updateLambda := {
      val resolvedBucketId = resolveBucketId(s3Bucket.value)

      val resolvedLambdaName = resolveLambdaName(lambdaName.value)

      val jar = sbtassembly.AssemblyKeys.assembly.value

      val result = pushJarToS3(jar, resolvedBucketId) match {
        case Success(s3Key) =>
          doUpdateLambda(resolvedLambdaName, resolvedBucketId, s3Key)
        case f: Failure =>
          f
      }

      result match {
        case s: Success[_] =>
          ()
        case f: Failure =>
          sys.error(s"Error updating lambda: ${f.exception.getLocalizedMessage}")
      }
    },
    createLambda := {
      val resolvedFunctionName = resolveLambdaName(lambdaName.value)
      val resolvedHandlerName = resolveHandlerName(handlerName.value)
      val resolvedRoleName = resolveRoleARN(roleArn.value)
      val resolvedBucketId = resolveBucketId(s3Bucket.value)

      val jar = sbtassembly.AssemblyKeys.assembly.value

      doCreateLambda(jar, resolvedFunctionName, resolvedHandlerName, resolvedRoleName, resolvedBucketId) match {
        case s: Success[_] =>
          ()
        case f: Failure =>
          sys.error(s"Failed to create lambda function: ${f.exception.getLocalizedMessage}")
      }
    },
    s3Bucket := None,
    lambdaName := None,
    handlerName := None,
    roleArn := None
  )

  private def resolveBucketId(sbtSettingValueOpt: Option[String]): S3BucketId = {
    sbtSettingValueOpt match {
      case Some(id) => S3BucketId(id)
      case None => sys.env.get(EnvironmentVariables.bucketId) match {
        case Some(envVarId) => S3BucketId(envVarId)
        case None => promptUserForS3BucketId()
      }
    }
  }

  private def resolveLambdaName(sbtSettingValueOpt: Option[String]): LambdaName = {
    sbtSettingValueOpt match {
      case Some(f) => LambdaName(f)
      case None => sys.env.get(EnvironmentVariables.lambdaName) match {
        case Some(envVarFunctionName) => LambdaName(envVarFunctionName)
        case None => promptUserForFunctionName()
      }
    }
  }

  private def resolveHandlerName(sbtSettingValueOpt: Option[String]): HandlerName = {
    sbtSettingValueOpt match {
      case Some(f) => HandlerName(f)
      case None => sys.env.get(EnvironmentVariables.handlerName) match {
        case Some(envVarFunctionName) => HandlerName(envVarFunctionName)
        case None => promptUserForHandlerName()
      }
    }
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

  private def pushJarToS3(jar: File, bucketId: S3BucketId): Result[S3Key] = {
    try{
      val amazonS3Client = new AmazonS3Client(credentials)

      val objectRequest = new PutObjectRequest(bucketId.value, jar.getName, jar)
      objectRequest.setCannedAcl(CannedAccessControlList.AuthenticatedRead)

      amazonS3Client.putObject(objectRequest)

      Success(S3Key(jar.getName))
    } catch {
      case ex @ (_ : AmazonClientException |
                 _ : AmazonServiceException) =>
        Failure(ex)
    }
  }

  private def doUpdateLambda(lambdaName: LambdaName, bucketId: S3BucketId, s3Key: S3Key): Result[UpdateFunctionCodeResult] = {
    try {
      val client = new AWSLambdaClient(credentials)

      val request = {
        val r = new UpdateFunctionCodeRequest()
        r.setFunctionName(lambdaName.value)
        r.setS3Bucket(bucketId.value)
        r.setS3Key(s3Key.value)

        r
      }

      val updateResult = client.updateFunctionCode(request)

      println(s"Updated lambda ${updateResult.getFunctionArn}")
      Success(updateResult)
    }
    catch {
      case ex @ (_ : AmazonClientException |
                 _ : AmazonServiceException) =>
        Failure(ex)
    }
  }

  private def doCreateLambda(jar: File,
                             functionName: LambdaName,
                             handlerName: HandlerName,
                             roleName: RoleARN,
                             s3BucketId: S3BucketId): Result[CreateFunctionResult] = {
    try {
      val client = new AWSLambdaClient(credentials)

      val request = {
        val r = new CreateFunctionRequest()
        r.setFunctionName(functionName.value)
        r.setHandler(handlerName.value)
        r.setRole(roleName.value)
        r.setRuntime(com.amazonaws.services.lambda.model.Runtime.Java8)

        val functionCode = {
          val c = new FunctionCode
          c.setS3Bucket(s3BucketId.value)
          c.setS3Key(jar.getName)
          c
        }

        r.setCode(functionCode)

        r
      }

      val createResult = client.createFunction(request)

      println(s"Created Lambda: ${createResult.getFunctionArn}")
      Success(createResult)
    }
    catch {
      case ex @ (_ : AmazonClientException |
                 _ : AmazonServiceException) =>
        Failure(ex)
    }
  }

  private def promptUserForS3BucketId(): S3BucketId = {
    val inputValue = readInput(s"Enter the AWS S3 bucket where the lambda jar will be stored. (You also could have set the environment variable: ${EnvironmentVariables.bucketId} or the sbt setting: s3Bucket)")

    S3BucketId(inputValue)
  }

  private def promptUserForFunctionName(): LambdaName = {
    val inputValue = readInput(s"Enter the name of the AWS Lambda. (You also could have set the environment variable: ${EnvironmentVariables.lambdaName} or the sbt setting: lambdaName)")

    LambdaName(inputValue)
  }

  private def promptUserForHandlerName(): HandlerName = {
    val inputValue = readInput(s"Enter the name of the AWS Lambda handler. (You also could have set the environment variable: ${EnvironmentVariables.handlerName} or the sbt setting: handlerName)")

    HandlerName(inputValue)
  }

  private def promptUserForRoleARN(): RoleARN = {
    import scala.collection.JavaConverters._
    val iamClient = new AmazonIdentityManagementClient(credentials)
    val existingRoles = iamClient.listRoles().getRoles.asScala

    val basicLambdaRoleName = "lambda_basic_execution"

    existingRoles.find(_.getRoleName == basicLambdaRoleName) match {
      case Some(basicRole) =>
        val reuseBasicRole = readInput("IAM role 'lambda_basic_execution' already exists. Reuse this role? (y/n)")
        
        if(reuseBasicRole == "y") RoleARN(basicRole.getArn)
        else readRoleARN()
      case None =>
        val createDefaultRole = readInput(s"Default IAM role for AWS Lambda has not been created yet. Create this role now? (y/n)")
        
        if(createDefaultRole == "y"){
          val createRoleRequest = {
            val policyDocument =
              """
                |{
                |  "Version": "2012-10-17",
                |  "Statement": [
                |    {
                |      "Effect": "Allow",
                |      "Action": [
                |        "logs:CreateLogGroup",
                |        "logs:CreateLogStream",
                |        "logs:PutLogEvents"
                |      ],
                |      "Resource": "arn:aws:logs:*:*:*"
                |    }
                |  ]
                |}
              """.stripMargin

            val c = new CreateRoleRequest
            c.setRoleName(basicLambdaRoleName)
            c.setAssumeRolePolicyDocument(URLEncoder.encode(policyDocument, "UTF-8"))
            c
          }

          val result = iamClient.createRole(createRoleRequest)
          RoleARN(result.getRole.getArn)
        }else{
          readRoleARN()
        }
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

  lazy val credentials = new ProfileCredentialsProvider()
}

sealed trait Result[+T]
case class Success[T](result: T) extends Result[T]
case class Failure(exception: Throwable) extends Result[Nothing]

case class S3BucketId(value: String)
case class S3Key(value: String)
case class LambdaName(value: String)
case class HandlerName(value: String)
case class RoleARN(value: String)
