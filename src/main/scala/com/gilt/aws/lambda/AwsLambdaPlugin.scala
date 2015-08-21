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
    val updateLambda = taskKey[Unit]("Package and deploy updated lambda function to AWS")
    val s3Bucket = settingKey[Option[String]]("S3 bucket where lambda function will be deployed to")
    val lambdaFunctionName = settingKey[Option[String]]("Name of the lambda function to update")
  }

  import autoImport._

  override def requires = sbtassembly.AssemblyPlugin

  override lazy val projectSettings = Seq(
    updateLambda := {
      val bucketId = resolveBucketId(s3Bucket.value)

      val functionName = resolveFunctionName(lambdaFunctionName.value)

      val jar = sbtassembly.AssemblyKeys.assembly.value

      val result = pushJarToS3(jar, bucketId) match {
        case Success(s3Key) =>
          doUpdateLambda(functionName, bucketId, s3Key)
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
      val functionName = resolveFunctionName(lambdaFunctionName.value)
      doCreateLambda(functionName) match {
        case s: Success[_] =>
          ()
        case f: Failure[_] =>
          sys.error(s"Failed to create lambda function: ${f.exception.getLocalizedMessage}")
      }
    },
    s3Bucket := None,
    lambdaFunctionName := None
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

  private def resolveFunctionName(sbtSettingValueOpt: Option[String]): FunctionName = {
    sbtSettingValueOpt match {
      case Some(f) => FunctionName(f)
      case None => sys.env.get("AWS_LAMBDA_FUNCTION_NAME") match {
        case Some(envVarFunctionName) => FunctionName(envVarFunctionName)
        case None => promptUserForFunctionName()
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

  private def doUpdateLambda(functionName: FunctionName, bucketId: S3BucketId, s3Key: S3Key): Result[UpdateFunctionCodeResult] = {
    try {
      val client = new AWSLambdaClient(credentials)

      val request = {
        val r = new UpdateFunctionCodeRequest()
        r.setFunctionName(functionName.value)
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

  private def doCreateLambda(functionName: FunctionName): Result[CreateFunctionResult] = {
    try {
      val client = new AWSLambdaClient(credentials)

      val request = {
        val r = new CreateFunctionRequest()
        r.setFunctionName(functionName.value)

        r
      }

      val createResult = client.createFunction(request)

      println(s"Created lambda ${createResult.getFunctionArn}")
      Success(createResult)
    }
    catch {
      case ex @ (_ : AmazonClientException |
                 _ : AmazonServiceException) =>
        Failure(ex.getLocalizedMessage, ex)
    }
  }

  def promptUserForS3BucketId(): S3BucketId = {
    SimpleReader.readLine("Enter the AWS S3 bucket where the lambda function will be stored\n") match {
      case Some(id) => S3BucketId(id)
      case None => promptUserForS3BucketId()
    }
  }

  def promptUserForFunctionName(): FunctionName = {
    SimpleReader.readLine("Enter the name of the lambda function to be executed\n") match {
      case Some(f) => FunctionName(f)
      case None => promptUserForFunctionName()
    }
  }

  lazy val credentials = new ProfileCredentialsProvider()
}

sealed trait Result[T]
case class Success[T](result: T) extends Result[T]
case class Failure[T](message: String, exception: Throwable) extends Result[T]

case class S3BucketId(value: String)
case class S3Key(value: String)
case class FunctionName(value: String)
