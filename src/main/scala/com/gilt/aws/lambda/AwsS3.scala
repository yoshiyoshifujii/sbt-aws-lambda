package com.gilt.aws.lambda

import com.amazonaws.{AmazonServiceException, AmazonClientException}
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{CannedAccessControlList, PutObjectRequest}
import sbt._

private[lambda] object AwsS3 {
  def pushJarToS3(jar: File, bucketId: S3BucketId): Result[S3Key] = {
    try{
      val amazonS3Client = new AmazonS3Client(AwsCredentials.provider)

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
}
