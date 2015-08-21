package com.gilt.aws.lambda

import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.services.lambda.AWSLambdaClient
import com.amazonaws.services.lambda.model._
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{CannedAccessControlList, PutObjectRequest}
import com.amazonaws.{AmazonClientException, AmazonServiceException}
import sbt._

object AwsLambdaPlugin extends AutoPlugin {

  object autoImport {
    val createLambda = taskKey[Unit]("Create a new AWS Lambda function from the current project")
    val updateLambda = taskKey[Unit]("Package and deploy the current project to an existing AWS Lambda")
    val s3Bucket = settingKey[Option[String]]("ID of the S3 bucket where the jar will be uploaded")
    val lambdaName = settingKey[Option[String]]("Name of the AWS Lambda to update")
    val handlerName = settingKey[Option[String]]("Name of the handler to be executed by AWS Lambda")
    val roleName = settingKey[Option[String]]("Name of the IAM role for the Lambda function")
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
        case f: Failure[_] =>
          f
      }

      result match {
        case s: Success[_] =>
          ()
        case f: Failure[_] =>
          sys.error(s"Error updating lambda: ${f.exception.getLocalizedMessage}")
      }
    },
    createLambda := {
      val resolvedFunctionName = resolveLambdaName(lambdaName.value)
      val resolvedHandlerName = resolveHandlerName(handlerName.value)
      val resolvedRoleName = resolveRoleName(roleName.value)
      val resolvedBucketId = resolveBucketId(s3Bucket.value)

      val jar = sbtassembly.AssemblyKeys.assembly.value

      doCreateLambda(jar, resolvedFunctionName, resolvedHandlerName, resolvedRoleName, resolvedBucketId) match {
        case s: Success[_] =>
          ()
        case f: Failure[_] =>
          sys.error(s"Failed to create lambda function: ${f.exception.getLocalizedMessage}")
      }
    },
    s3Bucket := None,
    lambdaName := None,
    handlerName := None,
    roleName := None
  )

  private def resolveBucketId(sbtSettingValueOpt: Option[String]): S3BucketId = {
    sbtSettingValueOpt match {
      case Some(id) => S3BucketId(id)
      case None => sys.env.get("AWS_LAMBDA_BUCKET_ID") match {
        case Some(envVarId) => S3BucketId(envVarId)
        case None => promptUserForS3BucketId()
      }
    }
  }

  private def resolveLambdaName(sbtSettingValueOpt: Option[String]): LambdaName = {
    sbtSettingValueOpt match {
      case Some(f) => LambdaName(f)
      case None => sys.env.get("AWS_LAMBDA_NAME") match {
        case Some(envVarFunctionName) => LambdaName(envVarFunctionName)
        case None => promptUserForFunctionName()
      }
    }
  }

  private def resolveHandlerName(sbtSettingValueOpt: Option[String]): HandlerName = {
    sbtSettingValueOpt match {
      case Some(f) => HandlerName(f)
      case None => sys.env.get("AWS_LAMBDA_HANDLER_NAME") match {
        case Some(envVarFunctionName) => HandlerName(envVarFunctionName)
        case None => promptUserForHandlerName()
      }
    }
  }

  private def resolveRoleName(sbtSettingValueOpt: Option[String]): RoleName = {
    sbtSettingValueOpt match {
      case Some(f) => RoleName(f)
      case None => sys.env.get("AWS_LAMBDA_IAM_ROLE_NAME") match {
        case Some(envVarFunctionName) => RoleName(envVarFunctionName)
        case None => promptUserForRoleName()
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
        Failure(ex.getLocalizedMessage, ex)
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
        Failure(ex.getLocalizedMessage, ex)
    }
  }

  private def doCreateLambda(jar: File,
                             functionName: LambdaName,
                             handlerName: HandlerName,
                             roleName: RoleName,
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
        Failure(ex.getLocalizedMessage, ex)
    }
  }

  private def promptUserForS3BucketId(): S3BucketId = {
    val inputValue = readInput("Enter the AWS S3 bucket where the lambda jar will be stored")

    S3BucketId(inputValue)
  }

  private def promptUserForFunctionName(): LambdaName = {
    val inputValue = readInput("Enter the name of the AWS Lambda")

    LambdaName(inputValue)
  }

  private def promptUserForHandlerName(): HandlerName = {
    val inputValue = readInput("Enter the name of the AWS Lambda handler")

    HandlerName(inputValue)
  }

  private def promptUserForRoleName(): RoleName = {
    val inputValue = readInput("Enter the name of the IAM role for the Lambda")

    RoleName(inputValue)
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

sealed trait Result[T]
case class Success[T](result: T) extends Result[T]
case class Failure[T](message: String, exception: Throwable) extends Result[T]

case class S3BucketId(value: String)
case class S3Key(value: String)
case class LambdaName(value: String)
case class HandlerName(value: String)
case class RoleName(value: String)
