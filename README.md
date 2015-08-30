# sbt-aws-lambda
sbt plugin to deploy code to AWS Lambda

Installation
------------

Add the following to your `project/plugins.sbt` file:

```scala
addSbtPlugin("com.gilt.sbt" % "sbt-aws-lambda" % "0.1.3")
```

Add the `AwsLambdaPlugin` auto-plugin to your build.sbt:

```scala
enablePlugins(AwsLambdaPlugin)
```



Usage
-------------
`sbt createLambda` creates a new AWS Lambda function from the current project.

`sbt updateLambda` updates an existing AWS Lambda function with the current project.


Configuration
-------------

sbt-aws-lambda can be configured using sbt settings, environment variables or by reading user input at deploy time

| sbt setting   |      Environment variable      |  Description |
|:----------|:-------------:|:------|
| s3Bucket |  AWS_LAMBDA_BUCKET_ID | The name of an S3 bucket where the lambda code will be stored |
| lambdaName |    AWS_LAMBDA_NAME   |   The name to use for this AWS Lambda function. Defaults to the project name |
| handlerName | AWS_LAMBDA_HANDLER_NAME |    Java class name and method to be executed, e.g. `com.gilt.example.Lambda::myMethod` |
| roleArn | AWS_LAMBDA_IAM_ROLE_ARN |The [ARN](http://docs.aws.amazon.com/general/latest/gr/aws-arns-and-namespaces.html "AWS ARN documentation") of an [IAM](https://aws.amazon.com/iam/ "AWS IAM documentation") role to use when creating a new Lambda |


