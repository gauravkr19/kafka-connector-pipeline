// Branch: main
// Permanent day-2 GitOps workflow: CREATE / UPDATE / DELETE complete CFK Connector YAMLs.

// Environment-specific values are supplied through Jenkins global/folder environment variables.
def cfg = [
    cloud            : env.KUBERNETES_CLOUD ?: 'kubernetes',
    serviceAccount   : env.JENKINS_AGENT_SERVICE_ACCOUNT ?: 'jenkins',
    jnlpImage        : env.JNLP_IMAGE ?: 'jenkins/inbound-agent:latest-jdk17',
    gitImage         : env.GIT_IMAGE ?: 'alpine/git:latest',
    imagePullSecret  : env.JENKINS_IMAGE_PULL_SECRET ?: '',
    gitOpsRepoUrl    : env.CONNECTOR_GIT_REPO_URL ?: 'REPLACE_ME',
    gitOpsBranch     : env.CONNECTOR_GIT_BRANCH ?: 'main',
    gitCredentialsId : env.CONNECTOR_GIT_CREDENTIALS_ID ?: 'connector-git-ssh'
]

properties([
    disableConcurrentBuilds(),
    buildDiscarder(logRotator(numToKeepStr: '30')),
    parameters([
        choice(name: 'OPERATION', choices: ['', 'CREATE', 'UPDATE', 'DELETE'], description: 'Connector GitOps operation'),
        choice(name: 'PHYSICAL_CLUSTER', choices: ['', 'poc', 'dev', 'preprod'], description: 'Physical Kafka cluster'),
        choice(name: 'LOGICAL_ENV', choices: ['', 'lab', 'dev', 'sit', 'sys', 'e2e', 'e2e2', 'prf', 'reg', 'pps', 'ide'], description: 'Logical environment'),
        string(name: 'CLASS_FOLDER', defaultValue: '', description: 'Git folder for connector class, for example postgres or oracle'),
        string(name: 'CONNECTOR_NAME', defaultValue: '', description: 'CFK Connector metadata.name and YAML filename'),
        text(name: 'CONNECTOR_YAML', defaultValue: '', description: 'Complete CFK Connector YAML. Required for CREATE and UPDATE; ignored for DELETE.'),
        string(name: 'CHANGE_TICKET', defaultValue: '', description: 'Optional change/RFC reference'),
        booleanParam(name: 'DRY_RUN', defaultValue: true, description: 'Show Git changes but do not commit/push')
    ])
])

// First multibranch run commonly exists only to create the parameter definitions.
if (!params.OPERATION) {
    echo 'Pipeline parameters are initialized. Run again with Build with Parameters.'
    return
}

def pullSecrets = cfg.imagePullSecret ? [cfg.imagePullSecret] : []

podTemplate(
    cloud: cfg.cloud,
    serviceAccount: cfg.serviceAccount,
    imagePullSecrets: pullSecrets,
    containers: [
        containerTemplate(name: 'git', image: cfg.gitImage, command: 'cat', ttyEnabled: true),
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

        stage('Validate Input') {
            helper.validateTarget(params.PHYSICAL_CLUSTER, params.LOGICAL_ENV)
            helper.validateSimpleName(params.CLASS_FOLDER, 'CLASS_FOLDER')
            helper.validateSimpleName(params.CONNECTOR_NAME, 'CONNECTOR_NAME')

            if (params.OPERATION in ['CREATE', 'UPDATE']) {
                if (!params.CONNECTOR_YAML?.trim()) {
                    error('CONNECTOR_YAML is required for CREATE and UPDATE.')
                }
            }
        }

        stage('Checkout Connector GitOps Repo') {
            container('git') {
                helper.checkoutRepo([
                    url: cfg.gitOpsRepoUrl,
                    branch: cfg.gitOpsBranch,
                    credentialsId: cfg.gitCredentialsId
                ], 'gitops')
            }
        }

        stage("${params.OPERATION} Connector YAML") {
            def target = helper.connectorPath(
                params.PHYSICAL_CLUSTER,
                params.LOGICAL_ENV,
                params.CLASS_FOLDER,
                params.CONNECTOR_NAME
            )

            dir('gitops') {
                if (params.OPERATION == 'CREATE') {
                    if (fileExists(target)) {
                        error("CREATE rejected: ${target} already exists. Use UPDATE.")
                    }
                    def parentDir = target.substring(0, target.lastIndexOf('/'))
                    sh "mkdir -p -- '${parentDir}'"
                    writeFile file: target, text: params.CONNECTOR_YAML.trim() + '\n'
                }
                else if (params.OPERATION == 'UPDATE') {
                    if (!fileExists(target)) {
                        error("UPDATE rejected: ${target} does not exist. Use CREATE or correct CLASS_FOLDER.")
                    }
                    writeFile file: target, text: params.CONNECTOR_YAML.trim() + '\n'
                }
                else if (params.OPERATION == 'DELETE') {
                    if (!fileExists(target)) {
                        error("DELETE rejected: ${target} does not exist.")
                    }
                    sh "rm -- '${target}'"
                }
                else {
                    error("Unsupported OPERATION: ${params.OPERATION}")
                }
            }
        }

        stage('Review Git Diff') {
            container('git') {
                helper.showGitDiff('gitops')
            }
        }

        if (params.DRY_RUN) {
            echo 'DRY_RUN=true: Git changes were not committed or pushed. ArgoCD will see no change.'
        }
        else {
            stage('Commit and Push') {
                container('git') {
                    def message = helper.buildCommitMessage(
                        params.OPERATION,
                        params.PHYSICAL_CLUSTER,
                        params.LOGICAL_ENV,
                        params.CONNECTOR_NAME,
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
