package com.gilt.aws.lambda

import com.amazonaws.{AmazonServiceException, AmazonClientException}
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{Bucket, CannedAccessControlList, PutObjectRequest}
import sbt._

private[lambda] object AwsS3 {
  private lazy val client = new AmazonS3Client(AwsCredentials.provider)

  def pushJarToS3(jar: File, bucketId: S3BucketId): Result[S3Key] = {
    try{
      val objectRequest = new PutObjectRequest(bucketId.value, jar.getName, jar)
      objectRequest.setCannedAcl(CannedAccessControlList.AuthenticatedRead)

      client.putObject(objectRequest)

      Success(S3Key(jar.getName))
    } catch {
      case ex @ (_ : AmazonClientException |
                 _ : AmazonServiceException) =>
        Failure(ex)
    }
  }

  def getBucket(bucketId: S3BucketId): Option[Bucket] = {
    import scala.collection.JavaConverters._
    client.listBuckets().asScala.find(_.getName == bucketId.value)
  }

  def createBucket(bucketId: S3BucketId): S3BucketId = {
    client.createBucket(bucketId.value)
    bucketId
  }
}
