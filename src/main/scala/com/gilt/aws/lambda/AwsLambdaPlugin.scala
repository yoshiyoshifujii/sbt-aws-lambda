package com.gilt.aws.lambda

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.CannedAccessControlList
import com.amazonaws.services.s3.model.PutObjectRequest
import sbt.AutoPlugin
import sbt._

import scala.util.control.NonFatal

object AwsLambdaPlugin extends AutoPlugin {

  object autoImport {
    val deployLambda = taskKey[Boolean]("Package and deploy lambda function to AWS")
  }

  import autoImport._

  override def requires = sbtassembly.AssemblyPlugin


  override lazy val projectSettings = Seq(
    deployLambda := deploy(sbtassembly.AssemblyKeys.assembly.value)
  )

  private def deploy(jar: File): Boolean = {
    val amazonS3Client = new AmazonS3Client(new DefaultAWSCredentialsProviderChain)

    try {
      val bucketId = "personalization-lambda-functions"
      val objectRequest = new PutObjectRequest(bucketId, jar.getName, jar)

      objectRequest.setCannedAcl(CannedAccessControlList.AuthenticatedRead)
      amazonS3Client.putObject(objectRequest)

      println(s"File ${jar.getName} uploaded to bucket $bucketId")
      true
    } catch {
      case NonFatal(e) => {
        println(s"Could not upload file ${jar.getCanonicalPath}: \n${e.getStackTraceString}")
        false
      }
    }
  }
}