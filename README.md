# Kafka Connector Jenkins Multibranch Pipeline

Two branch-specific Jenkins pipelines:

- `adopt-migrate`: temporary migration workflow using `connectorctl`.
- `main`: permanent GitOps CRUD workflow for complete CFK `Connector` YAML files.

Both modify the same Connector GitOps repository. ArgoCD watches that GitOps repository, not the Jenkins pipeline repository.

## Repository layout

Put these files on each branch of the Jenkins multibranch SCM repository:

```text
adopt-migrate branch
├── Jenkinsfile
└── pipeline/connectorPipeline.groovy

main branch
├── Jenkinsfile
└── pipeline/connectorPipeline.groovy
```

`pipeline/connectorPipeline.groovy` is intentionally identical on both branches. In the office environment it can later move into the existing Jenkins Shared Library with minimal change.

## Jenkins plugins used

The design intentionally uses standard capabilities already present in the supplied pipeline pattern:

- Pipeline
- Git
- Credentials Binding
- Kubernetes plugin

No Active Choices, file-upload, lockable-resources, or other UI plugin is required.

## Jenkins environment configuration

Configure these as Jenkins global/folder environment variables so the Jenkinsfiles do not change between GKE and OpenShift.

| Variable | GKE example | Office example |
|---|---|---|
| `KUBERNETES_CLOUD` | `kubernetes` | existing Kubernetes cloud name |
| `JENKINS_AGENT_SERVICE_ACCOUNT` | `jenkins` | e.g. `jenkins-admin` if genuinely required |
| `JNLP_IMAGE` | `jenkins/inbound-agent:latest-jdk17` | internal Quay inbound-agent image |
| `GIT_IMAGE` | `alpine/git:latest` | internal Git tools image |
| `GO_IMAGE` | `golang:1.27` | internal Go image |
| `CONNECTORCTL_RUNTIME_IMAGE` | image containing `kubectl` | internal image containing `oc`/`kubectl` |
| `JENKINS_IMAGE_PULL_SECRET` | blank for public images | existing Quay pull secret |
| `CONNECTOR_GIT_REPO_URL` | SSH URL for connector GitOps repo | internal SSH Git URL |
| `CONNECTOR_GIT_BRANCH` | `main` | desired ArgoCD-watched branch |
| `CONNECTOR_GIT_CREDENTIALS_ID` | Jenkins SSH credential ID | office credential ID |
| `CONNECTORCTL_REPO_URL` | SSH URL for connectorctl source | internal connectorctl Git URL |
| `CONNECTORCTL_BRANCH` | `main` | connectorctl branch |
| `CONNECTORCTL_GIT_CREDENTIALS_ID` | Jenkins SSH credential ID | office credential ID |

The Git credential is expected to be **SSH Username with private key** because the pipeline performs authenticated `git push` after checkout.

## GKE test prerequisites

1. Jenkins is configured with Kubernetes Cloud access to the GKE cluster.
2. The agent service account exists.
3. If `connectorctl --server-dry-run` is used, the agent service account has the required Kubernetes RBAC in the namespace containing CFK `Connector` resources.
4. Jenkins has SSH credentials for both Git repositories.
5. The `connectorctl` runtime image contains the executable selected by `KubectlBin` in your connectorctl config (`kubectl` in GKE; typically `oc` or `kubectl` in OpenShift).
6. ArgoCD watches `CONNECTOR_GIT_BRANCH` in the Connector GitOps repository.

No privileged pod and no `/var/lib/containers` hostPath are required because this pipeline does not build container images or use Docker/Buildah/Podman.

## Main branch Git layout

The permanent pipeline writes exactly:

```text
connectors/<physical-cluster>/<logical-env>/<class-folder>/<connector-name>.yaml
```

Examples:

```text
connectors/dev/sit/postgres/ngil-sit-orders-pg.yaml
connectors/preprod/reg/oracle/ngil-reg-orders-oracle.yaml
```

`CREATE` fails if the target exists. `UPDATE` and `DELETE` fail if the target does not exist. The pipeline deliberately does not interpret connector configuration, secret paths, plugin classes, or CFK semantics.

For `CREATE` and `UPDATE`, users paste the complete CFK Connector YAML into the standard Jenkins multi-line `CONNECTOR_YAML` parameter. This avoids introducing a file-upload plugin.

## ADOPT

The Jenkinsfile calls the already-tested shape:

```text
connectorctl --config-dir ... --repo-root ... adopt \
  --cluster ... --env ... --names-file ... --concurrency ... \
  --validate-live --server-dry-run
```

`DRY_RUN` adds `--dry-run`; a non-empty change ticket adds `--change-ticket`.

## MIGRATE adapter

The exact final migrate CLI flag syntax was not available while this scaffold was generated. The Jenkinsfile isolates it under the comment `MIGRATE ADAPTER` and currently assumes:

```text
migrate --cluster SOURCE --env SOURCE_ENV \
  --target-cluster TARGET --target-env TARGET_ENV \
  --names-file FILE --concurrency N
```

If the actual Go CLI uses different target flag names, change only that argument block. The rest of the pipeline remains unchanged.

## Git concurrency

The two multibranch child jobs can run at the same time and both push to the same Connector GitOps branch. Rather than require the Lockable Resources plugin, the helper commits locally, fetches the latest target branch, rebases, and then pushes. A real conflict fails the build instead of silently overwriting another connector change.

## Office Shared Library migration

After GKE testing, move functions from `pipeline/connectorPipeline.groovy` into your existing Shared Library, for example `vars/connectorGitOps.groovy`, then replace:

```groovy
def helper = load 'pipeline/connectorPipeline.groovy'
```

with your existing `@Library(...) _` import and Shared Library function calls. Branch orchestration and parameters can remain unchanged.

## MIGRATE lab note

`connectorctl migrate` uses `--source-cluster`, `--source-env`, `--target-cluster`, `--target-env`, optional `--ticket`, and `--write` to enable writes. Dry-run is the default when `--write` is omitted. The lab environment mapping includes both `poc/lab` and `poc/lab2` so MIGRATE can be tested within one GKE Kafka lab. `config-poc` must also define the corresponding `lab2` mapping before running that test.
# kafka-connector-pipeline
