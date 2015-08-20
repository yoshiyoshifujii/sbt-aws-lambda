package com.gilt.aws.lambda

import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{CannedAccessControlList, PutObjectRequest}
import sbt._

import scala.util.control.NonFatal

object AwsLambdaPlugin extends AutoPlugin {

  object autoImport {
    val deployLambda = taskKey[Boolean]("Package and deploy lambda function to AWS")
    val s3Bucket = settingKey[Option[String]]("S3 bucket where lambda function will be deployed to")
  }

  import autoImport._

  override def requires = sbtassembly.AssemblyPlugin

  override lazy val projectSettings = Seq(
    deployLambda := deploy(sbtassembly.AssemblyKeys.assembly.value, s3Bucket.value),
    s3Bucket := None
  )

  private def deploy(jar: File, bucketIdOpt: Option[String]): Boolean = {
    val amazonS3Client = new AmazonS3Client(new ProfileCredentialsProvider)

    val bucketId = bucketIdOpt match {
      case Some(id) => id
      case None => sys.env.get("AWS_LAMBDA_BUCKET_ID") match {
        case Some(envVarId) => envVarId
        case None => promptUserForS3BucketId()
      }
    }
    
    try {
      val objectRequest = new PutObjectRequest(bucketId, jar.getName, jar)

      objectRequest.setCannedAcl(CannedAccessControlList.AuthenticatedRead)
      amazonS3Client.putObject(objectRequest)

      println(s"File ${jar.getName} uploaded to bucket $bucketIdOpt")
      true
    } catch {
      case NonFatal(e) => {
        println(s"Could not upload file ${jar.getCanonicalPath}: ${e.getMessage}\n${e.getStackTraceString}")
        false
      }
    }
  }

  def promptUserForS3BucketId(): String = {

    SimpleReader.readLine("Enter the AWS S3 bucket where the lambda function will be stored\n") match {
      case Some(id) => id
      case None => promptUserForS3BucketId()
    }
  }
}