package com.gilt.aws.lambda

sealed trait Result[+T]
case class Success[T](result: T) extends Result[T]
case class Failure(exception: Throwable) extends Result[Nothing]

case class Region(value: String)
case class S3BucketId(value: String)
case class S3Key(value: String)
case class LambdaName(value: String)
case class LambdaARN(value: String)
case class HandlerName(value: String)
case class RoleARN(value: String)

object EnvironmentVariables {
  val region = "AWS_REGION"
  val bucketId = "AWS_LAMBDA_BUCKET_ID"
  val lambdaName = "AWS_LAMBDA_NAME"
  val handlerName = "AWS_LAMBDA_HANDLER_NAME"
  val roleArn = "AWS_LAMBDA_IAM_ROLE_ARN"
}
