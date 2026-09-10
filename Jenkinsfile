// Branch: adopt-migrate
// Temporary migration workflow. connectorctl remains responsible for ADOPT/MIGRATE logic.

def cfg = [
    cloud                    : env.KUBERNETES_CLOUD ?: 'kubernetes',
    serviceAccount           : env.JENKINS_AGENT_SERVICE_ACCOUNT ?: 'jenkins',
    jnlpImage                : env.JNLP_IMAGE ?: 'jenkins/inbound-agent:latest-jdk17',
    gitImage                 : env.GIT_IMAGE ?: 'jenkins/inbound-agent:latest-jdk17',
    goImage                  : env.GO_IMAGE ?: 'golang:1.27',
    runtimeImage             : env.CONNECTORCTL_RUNTIME_IMAGE ?: 'bitnami/kubectl:latest',
    imagePullSecret          : env.JENKINS_IMAGE_PULL_SECRET ?: '',
    gitOpsRepoUrl            : env.CONNECTOR_GIT_REPO_URL ?: 'REPLACE_ME',
    gitOpsBranch             : env.CONNECTOR_GIT_BRANCH ?: 'main',
    gitCredentialsId         : env.CONNECTOR_GIT_CREDENTIALS_ID ?: 'connector-git-ssh',
    connectorctlRepoUrl      : env.CONNECTORCTL_REPO_URL ?: 'REPLACE_ME',
    connectorctlBranch       : env.CONNECTORCTL_BRANCH ?: 'main',
    connectorctlCredentialsId: env.CONNECTORCTL_GIT_CREDENTIALS_ID ?: (env.CONNECTOR_GIT_CREDENTIALS_ID ?: 'connector-git-ssh')
]

properties([
    disableConcurrentBuilds(),
    buildDiscarder(logRotator(numToKeepStr: '30')),
    parameters([
        choice(name: 'OPERATION', choices: ['', 'ADOPT', 'MIGRATE'], description: 'connectorctl operation'),
        choice(name: 'PHYSICAL_CLUSTER', choices: ['', 'poc', 'dev', 'preprod'], description: 'Source physical Kafka cluster'),
        choice(name: 'LOGICAL_ENV', choices: ['', 'lab', 'dev', 'sit', 'sys', 'e2e', 'e2e2', 'prf', 'reg', 'pps', 'ide'], description: 'Source logical environment'),
        text(name: 'CONNECTOR_NAMES', defaultValue: '', description: 'One connector name per line. Comma-separated input is also accepted.'),
        choice(name: 'TARGET_PHYSICAL_CLUSTER', choices: ['', 'poc', 'dev', 'preprod'], description: 'MIGRATE only: target physical cluster'),
        choice(name: 'TARGET_LOGICAL_ENV', choices: ['', 'lab', 'dev', 'sit', 'sys', 'e2e', 'e2e2', 'prf', 'reg', 'pps', 'ide'], description: 'MIGRATE only: target logical environment'),
        choice(name: 'CONCURRENCY', choices: ['4', '1', '2', '8'], description: 'connectorctl worker concurrency'),
        string(name: 'CHANGE_TICKET', defaultValue: '', description: 'Optional change/RFC reference'),
        booleanParam(name: 'DRY_RUN', defaultValue: true, description: 'Pass connectorctl dry-run and do not push Git changes')
    ])
])

if (!params.OPERATION) {
    echo 'Pipeline parameters are initialized. Run again with Build with Parameters.'
    return
}

def pullSecrets = cfg.imagePullSecret ? [cfg.imagePullSecret] : []

podTemplate(
    cloud: cfg.cloud,
    serviceAccount: cfg.serviceAccount,
    imagePullSecrets: pullSecrets,
    runAsUser: "1000",
    runAsGroup: "1000",

    containers: [
        containerTemplate(name: 'git', image: cfg.gitImage, command: 'cat', ttyEnabled: true),
        containerTemplate(name: 'go', image: cfg.goImage, command: 'cat', ttyEnabled: true),
        containerTemplate(name: 'runtime', image: cfg.runtimeImage, command: 'cat', ttyEnabled: true),
        containerTemplate(name: 'jnlp', image: cfg.jnlpImage, args: '${computer.jnlpmac} ${computer.name}')
    ]
) {
    node(POD_LABEL) {
        stage('Checkout Pipeline') {
            container('git') {
                checkout scm
            }
        }

        def helper = load 'pipeline/connectorPipeline.groovy'
        def connectorNames = []

        stage('Validate Input') {
            helper.validateTarget(params.PHYSICAL_CLUSTER, params.LOGICAL_ENV)
            connectorNames = helper.requireConnectorNames(params.CONNECTOR_NAMES)

            if (params.OPERATION == 'MIGRATE') {
                helper.validateTarget(params.TARGET_PHYSICAL_CLUSTER, params.TARGET_LOGICAL_ENV)
                if (params.PHYSICAL_CLUSTER == params.TARGET_PHYSICAL_CLUSTER &&
                    params.LOGICAL_ENV == params.TARGET_LOGICAL_ENV) {
                    error('MIGRATE source and target cannot be identical.')
                }
            }
        }

        stage('Checkout Connector Repositories') {
            container('git') {
                helper.checkoutRepo([
                    url: cfg.gitOpsRepoUrl,
                    branch: cfg.gitOpsBranch,
                    credentialsId: cfg.gitCredentialsId
                ], 'gitops')

                helper.checkoutRepo([
                    url: cfg.connectorctlRepoUrl,
                    branch: cfg.connectorctlBranch,
                    credentialsId: cfg.connectorctlCredentialsId
                ], 'connectorctl')
            }
        }

        stage('Build connectorctl') {
            container('go') {
                dir('connectorctl') {
                    sh '''
                        set -eu

                        export HOME=/tmp
                        export GOCACHE=/tmp/go-build
                        export GOPATH=/tmp/go
                        export GOMODCACHE=/tmp/go/pkg/mod

                        mkdir -p "$GOCACHE" "$GOMODCACHE" bin

                        CGO_ENABLED=0 go build -o bin/connectorctl ./cmd/connectorctl
                        ./bin/connectorctl --help >/dev/null
                    '''
                }
            }
        }

        stage("connectorctl ${params.OPERATION}") {
            writeFile file: 'connector-names.txt', text: connectorNames.join('\n') + '\n'

            container('runtime') {
                withEnv([
                    "OPERATION=${params.OPERATION}",
                    "PHYSICAL_CLUSTER=${params.PHYSICAL_CLUSTER}",
                    "LOGICAL_ENV=${params.LOGICAL_ENV}",
                    "TARGET_PHYSICAL_CLUSTER=${params.TARGET_PHYSICAL_CLUSTER ?: ''}",
                    "TARGET_LOGICAL_ENV=${params.TARGET_LOGICAL_ENV ?: ''}",
                    "CONCURRENCY=${params.CONCURRENCY}",
                    "CHANGE_TICKET=${params.CHANGE_TICKET ?: ''}",
                    "DRY_RUN=${params.DRY_RUN}",
                    "WORKSPACE_ROOT=${pwd()}"
                ]) {
                    sh '''
                        set -eu

                        BIN="${WORKSPACE_ROOT}/connectorctl/bin/connectorctl"
                        REPO_ROOT="${WORKSPACE_ROOT}/gitops"
                        NAMES_FILE="${WORKSPACE_ROOT}/connector-names.txt"

                        # Lab uses config-poc. Office dev/preprod use config.
                        if [ "${PHYSICAL_CLUSTER}" = "poc" ]; then
                            CONFIG_DIR="${WORKSPACE_ROOT}/connectorctl/config-poc"
                        else
                            CONFIG_DIR="${WORKSPACE_ROOT}/connectorctl/config"
                        fi

                        if [ "${OPERATION}" = "ADOPT" ]; then
                            set -- "${BIN}" \
                              --config-dir "${CONFIG_DIR}" \
                              --repo-root "${REPO_ROOT}" \
                              adopt \
                              --cluster "${PHYSICAL_CLUSTER}" \
                              --env "${LOGICAL_ENV}" \
                              --names-file "${NAMES_FILE}" \
                              --concurrency "${CONCURRENCY}" \
                              --validate-live \
                              --server-dry-run

                            if [ -n "${CHANGE_TICKET:-}" ]; then
                                set -- "$@" --ticket "${CHANGE_TICKET}"
                            fi

                            if [ "${DRY_RUN:-true}" != "true" ]; then
                                set -- "$@" --write
                            fi
                            "$@"

                        elif [ "${OPERATION}" = "MIGRATE" ]; then
                            # runMigrate uses source/target flags. Dry-run is the default;
                            # --write explicitly enables writes to the Git worktree.
                            set -- "${BIN}" \
                              --config-dir "${CONFIG_DIR}" \
                              --repo-root "${REPO_ROOT}" \
                              migrate \
                              --source-cluster "${PHYSICAL_CLUSTER}" \
                              --source-env "${LOGICAL_ENV}" \
                              --target-cluster "${TARGET_PHYSICAL_CLUSTER}" \
                              --target-env "${TARGET_LOGICAL_ENV}" \
                              --names-file "${NAMES_FILE}" \
                              --concurrency "${CONCURRENCY}" \
                              --validate-live \
                              --server-dry-run

                            if [ -n "${CHANGE_TICKET:-}" ]; then
                                set -- "$@" --ticket "${CHANGE_TICKET}"
                            fi
                            if [ "${DRY_RUN:-true}" != "true" ]; then
                                set -- "$@" --write
                            fi
                            # --check-live defaults to true in connectorctl; no flag needed.
                            "$@"
                        else
                            echo "Unsupported OPERATION: ${OPERATION}" >&2
                            exit 2
                        fi
                    '''
                }
            }
        }

        stage('Archive connectorctl Reports') {
            archiveArtifacts artifacts: 'gitops/reports/**/*.json,gitops/render/**/*', allowEmptyArchive: true
            // Runtime reports/render output are Jenkins artifacts, not desired-state Git.
            dir('gitops') {
                sh 'rm -rf reports render'
            }
        }

        stage('Review Git Diff') {
            container('git') {
                helper.showGitDiff('gitops')
            }
        }

        if (params.DRY_RUN) {
            echo 'DRY_RUN=true: connectorctl/Git changes were not pushed.'
        }
        else {
            stage('Commit and Push') {
                container('git') {
                    def ref = connectorNames.size() == 1 ? connectorNames[0] : "${connectorNames.size()}-connectors"
                    def message = helper.buildCommitMessage(
                        params.OPERATION,
                        params.PHYSICAL_CLUSTER,
                        params.LOGICAL_ENV,
                        ref,
                        params.CHANGE_TICKET
                    )
                    helper.commitAndPush([
                        branch: cfg.gitOpsBranch,
                        credentialsId: cfg.gitCredentialsId
                    ], 'gitops', message)
                }
            }
        }
    }
}
