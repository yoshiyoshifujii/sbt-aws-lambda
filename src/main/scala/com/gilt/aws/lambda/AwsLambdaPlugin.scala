package com.gilt.aws.lambda

import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.services.lambda.AWSLambdaClient
import com.amazonaws.services.lambda.model.UpdateFunctionCodeRequest
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{PutObjectResult, CannedAccessControlList, PutObjectRequest}
import sbt._

object AwsLambdaPlugin extends AutoPlugin {

  object autoImport {
    val deployLambda = taskKey[Unit]("Package and deploy lambda function to AWS")
    val s3Bucket = settingKey[Option[String]]("S3 bucket where lambda function will be deployed to")
    val lambdaFunctionName = settingKey[Option[String]]("Name of the lambda function to update")
  }

  import autoImport._

  override def requires = sbtassembly.AssemblyPlugin

  override lazy val projectSettings = Seq(
    deployLambda := {
      val bucketId = s3Bucket.value match {
        case Some(id) => id
        case None => sys.env.get("AWS_LAMBDA_BUCKET_ID") match {
          case Some(envVarId) => envVarId
          case None => promptUserForS3BucketId()
        }
      }

      val functionName = lambdaFunctionName.value match {
        case Some(f) => f
        case None => sys.env.get("AWS_LAMBDA_FUNCTION_NAME") match {
          case Some(envVarFunctionName) => envVarFunctionName
          case None => promptUserForFunctionName()
        }
      }

      val jar = sbtassembly.AssemblyKeys.assembly.value

      deploy(jar, bucketId)
      updateLambda(functionName, bucketId, jar.getName)
    },
    s3Bucket := None,
    lambdaFunctionName := None
  )

  private def deploy(jar: File, bucketId: String): PutObjectResult = {
    val amazonS3Client = new AmazonS3Client(credentials)

    val objectRequest = new PutObjectRequest(bucketId, jar.getName, jar)

    objectRequest.setCannedAcl(CannedAccessControlList.AuthenticatedRead)
    amazonS3Client.putObject(objectRequest)
  }

  private def updateLambda(functionName: String, bucketId: String, s3Key: String) = {
    val client = new AWSLambdaClient(credentials)

    val request = {
      val r = new UpdateFunctionCodeRequest()
      r.setFunctionName(functionName)
      r.setS3Bucket(bucketId)
      r.setS3Key(s3Key)

      r
    }

    client.updateFunctionCode(request)
  }

  def promptUserForS3BucketId(): String = {
    SimpleReader.readLine("Enter the AWS S3 bucket where the lambda function will be stored\n") match {
      case Some(id) => id
      case None => promptUserForS3BucketId()
    }
  }

  def promptUserForFunctionName(): String = {
    SimpleReader.readLine("Enter the name of the lambda function to be executed\n") match {
      case Some(f) => f
      case None => promptUserForFunctionName()
    }
  }

  lazy val credentials = new ProfileCredentialsProvider()
}