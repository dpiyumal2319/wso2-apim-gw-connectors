# API-Scoped API Key Lambda Authorizer (AWS REST API Gateway)

This Lambda authorizer validates API keys from the `ApiKey` request header and
enforces API-level scope using API key tags.

## What It Validates

1. `ApiKey` header is present.
2. Header value matches an enabled API key in API Gateway.
3. API key has a `wso2:api-id` tag matching the target API ID in `methodArn`.

## Required API Key Tag

- `wso2:api-id`
  - Must contain exactly one AWS API ID value.
  - Example: `a1b2c3d4e5`

### Behavior

- If `wso2:api-id` is missing or empty: deny.
- If `wso2:api-id` is different from request API ID: deny.
- If `wso2:api-id` matches request API ID: allow.

## Required Lambda IAM Permissions

Grant this Lambda execution role read access to API Gateway API keys and tags:

- `apigateway:GET` on `/apikeys`
- `apigateway:GET` on `/apikeys/*`
- `apigateway:GET` on `/apikeys/*/tags`

## Runtime Config (Environment Variables)

- `CACHE_TTL_SECONDS` (default: `60`)
- `LOG_LEVEL` (`debug`, `info`, `warn`, `error`; default: `info`)

## API Gateway Authorizer Wiring (Manual)

Recommended for this handler:

- Authorizer type: `REQUEST`
- Identity source: `method.request.header.ApiKey`
- Result TTL: `0` for strict real-time checks (or higher if desired)

No deployment/wiring automation is included in this repository.

## Step-by-Step Setup

### 1. Package the Lambda

From this folder, create a zip with `index.js`.

```bash
zip authorizer.zip index.js
```

### 2. Create Lambda Execution Role

Create an IAM role trusted by Lambda, then attach permissions:

- `apigateway:GET` on `/apikeys`
- `apigateway:GET` on `/apikeys/*`
- `apigateway:GET` on `/apikeys/*/tags`
- CloudWatch logs permissions (`logs:CreateLogGroup`, `logs:CreateLogStream`, `logs:PutLogEvents`)

### 3. Create Lambda Function

Create the function with runtime Node.js 20 and upload `authorizer.zip`.
Set handler to:

```text
index.handler
```

Set optional environment variables:

- `CACHE_TTL_SECONDS=60`
- `LOG_LEVEL=info`

### 4. Allow API Gateway to Invoke Lambda

Run (replace placeholders):

```bash
aws lambda add-permission \
  --function-name <LAMBDA_NAME_OR_ARN> \
  --statement-id apigw-authorizer-invoke \
  --action lambda:InvokeFunction \
  --principal apigateway.amazonaws.com \
  --source-arn "arn:aws:execute-api:<REGION>:<ACCOUNT_ID>:<API_ID>/authorizers/*"
```

### 5. Create API Gateway Authorizer

In REST API Gateway, create a Lambda Authorizer with:

- Type: `REQUEST`
- Lambda: your function
- Identity source: `method.request.header.ApiKey`
- Result TTL: `0` (or higher if you accept cache delay)

### 6. Attach Authorizer to API Methods

For each protected method:

- Authorization: `CUSTOM`
- Custom authorizer: the authorizer created above
- API Key Required: `false` (this flow validates header `ApiKey`, not native `x-api-key`)

Redeploy the API stage after changes.

### 7. Tag Each API Key With Target API ID

Each key must have one tag:

- Key: `wso2:api-id`
- Value: target AWS API ID (for example `a1b2c3d4e5`)

Example:

```bash
aws apigateway tag-resource \
  --resource-arn "arn:aws:apigateway:<REGION>::/apikeys/<API_KEY_ID>" \
  --tags "wso2:api-id=<API_ID>"
```

### 8. Test

Invoke an API method with:

```bash
curl -H "ApiKey: <API_KEY_VALUE>" \
  "https://<API_ID>.execute-api.<REGION>.amazonaws.com/<STAGE>/<RESOURCE_PATH>"
```

Expected:

- `Allow` when key is valid/enabled and tag matches API ID.
- `Deny` when key is missing/invalid/disabled, tag is missing, or tag API ID does not match.
