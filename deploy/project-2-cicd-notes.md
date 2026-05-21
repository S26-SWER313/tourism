# Project 2 CI/CD Notes

## Trigger rule

The workflow is defined in `.github/workflows/deploy-ecs.yml` and runs on every push to the `main` branch.

## AWS authentication

The workflow uses GitHub OIDC with `aws-actions/configure-aws-credentials@v4`. Store the IAM role ARN in the repository secret `AWS_ROLE_TO_ASSUME`. The role should trust GitHub Actions for this repository and grant only the permissions needed to:

- get an ECR authorization token
- push images to the team's ECR repository
- describe the current ECS service and task definition
- register a new ECS task definition revision
- update the ECS service
- pass the ECS task execution role and task role used by the service

Database passwords are not stored in GitHub. They remain in AWS Secrets Manager and continue to be injected into the ECS task at runtime by the existing task definition.

## Required repository variables

Configure these GitHub repository variables before running the workflow:

- `AWS_REGION`: AWS region, for example `us-east-1`
- `ECR_REPOSITORY`: ECR repository name, for example `tourism-hotel-booking`
- `ECS_CLUSTER`: existing ECS cluster name
- `ECS_SERVICE`: existing ECS service name
- `ECS_CONTAINER_NAME`: container name inside the ECS task definition
- `ALB_URL`: deployed Application Load Balancer base URL, for example `http://example-alb.us-east-1.elb.amazonaws.com`

## Image versioning

Each Docker image is tagged with the full Git commit SHA using `${{ github.sha }}`. The workflow also pushes a `latest` tag for convenience, but deployments use the immutable commit-SHA tag.

## Deployment flow

The workflow checks out the repository, sets up Java 21, runs `./mvnw -B test`, builds the Docker image from `tourism/Dockerfile`, pushes the image to ECR, downloads the currently running ECS task definition, renders a new task definition revision with the new image, and deploys that revision to the existing ECS service. The deploy action waits until the ECS service becomes stable.

## Smoke test

After deployment, the workflow calls:

```text
GET ${ALB_URL}/actuator/health
```

The run passes only if the endpoint returns HTTP 200.

## Rollback

To roll back, redeploy the previous ECS task definition revision from the ECS console or AWS CLI. Example:

```bash
aws ecs update-service \
  --cluster <cluster-name> \
  --service <service-name> \
  --task-definition <task-family>:<previous-revision>
```

ECR keeps the image tagged by commit SHA, so a previous task definition revision points back to the exact image that was deployed.

## Submission evidence checklist

- Screenshot of a successful GitHub Actions workflow run
- Screenshot of ECR showing the commit-SHA image tag and optional `latest` tag
- Screenshot of ECS service events showing successful deployment and a running new task revision
- Deployed ALB base URL
- Screenshot or terminal output showing `${ALB_URL}/actuator/health` returns HTTP 200 and `UP`
