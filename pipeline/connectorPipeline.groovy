// Common helper for Kafka Connector Jenkins pipelines.
// Keep this file identical on both multibranch branches.

@NonCPS
def allowedEnvironments() {
    return [
        poc    : ['lab', 'lab2'],
        dev    : ['dev', 'sit', 'sys', 'e2e', 'e2e2', 'prf'],
        preprod: ['reg', 'pps', 'ide']
    ]
}

def validateTarget(String physicalCluster, String logicalEnv) {
    def cluster = physicalCluster?.trim()?.toLowerCase()
    def envName = logicalEnv?.trim()?.toLowerCase()
    def mapping = allowedEnvironments()

    if (!cluster || !envName) {
        error('PHYSICAL_CLUSTER and LOGICAL_ENV are required.')
    }
    if (!mapping.containsKey(cluster)) {
        error("Unsupported PHYSICAL_CLUSTER: ${physicalCluster}")
    }
    if (!mapping[cluster].contains(envName)) {
        error("LOGICAL_ENV '${logicalEnv}' is not valid for PHYSICAL_CLUSTER '${physicalCluster}'.")
    }
}

def validateSimpleName(String value, String parameterName) {
    def v = value?.trim()
    if (!v) {
        error("${parameterName} is required.")
    }
    if (!(v ==~ /[A-Za-z0-9._-]+/)) {
        error("${parameterName} contains unsupported characters: '${value}'")
    }
    return v
}

@NonCPS
def parseConnectorNames(String rawNames) {
    return (rawNames ?: '')
        .split(/[\r\n,]+/)
        .collect { it.trim() }
        .findAll { it }
        .unique()
}

def requireConnectorNames(String rawNames) {
    def names = parseConnectorNames(rawNames)
    if (names.isEmpty()) {
        error('CONNECTOR_NAMES must contain at least one connector name.')
    }
    names.each { validateSimpleName(it, 'CONNECTOR_NAME') }
    return names
}

def checkoutRepo(Map cfg, String directory) {
    if (!cfg.url || cfg.url == 'REPLACE_ME') {
        error("Repository URL for ${directory} is not configured.")
    }
    dir(directory) {
        deleteDir()
        git branch: cfg.branch, credentialsId: cfg.credentialsId, url: cfg.url
    }
}

def connectorPath(String physicalCluster, String logicalEnv, String classFolder, String connectorName) {
    def cluster = validateSimpleName(physicalCluster.toLowerCase(), 'PHYSICAL_CLUSTER')
    def envName = validateSimpleName(logicalEnv.toLowerCase(), 'LOGICAL_ENV')
    def folder = validateSimpleName(classFolder, 'CLASS_FOLDER')
    def name = validateSimpleName(connectorName, 'CONNECTOR_NAME')
    return "connectors/${cluster}/${envName}/${folder}/${name}.yaml"
}

def showGitDiff(String directory) {
    dir(directory) {
        sh '''
            set -eu
            # Intent-to-add makes CREATE content visible in git diff without staging it.
            git add -N . 2>/dev/null || true
            git status --short
            echo '--- Git diff ---'
            git diff -- . || true
        '''
    }
}

def commitAndPush(Map cfg, String directory, String commitMessage) {
    dir(directory) {
        withCredentials([sshUserPrivateKey(
            credentialsId: cfg.credentialsId,
            keyFileVariable: 'GIT_SSH_KEY',
            usernameVariable: 'GIT_SSH_USER'
        )]) {
            withEnv([
                "TARGET_GIT_BRANCH=${cfg.branch}",
                "GIT_COMMIT_MESSAGE=${commitMessage}",
                "GIT_AUTHOR_NAME=${cfg.authorName ?: 'jenkins-kafka-connectors'}",
                "GIT_AUTHOR_EMAIL=${cfg.authorEmail ?: 'jenkins-kafka-connectors@local'}"
            ]) {
                sh '''
                    set -eu
                    export GIT_SSH_COMMAND="ssh -i ${GIT_SSH_KEY} -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new"

                    git config user.name "${GIT_AUTHOR_NAME}"
                    git config user.email "${GIT_AUTHOR_EMAIL}"
                    git add -A

                    if git diff --cached --quiet; then
                        echo 'No Git changes detected; nothing to commit.'
                        exit 0
                    fi

                    git commit -m "${GIT_COMMIT_MESSAGE}"

                    # Both multibranch jobs can modify the same GitOps branch.
                    # Rebase before push so independent connector changes do not require a locking plugin.
                    git fetch origin "${TARGET_GIT_BRANCH}"
                    git rebase "origin/${TARGET_GIT_BRANCH}"
                    git push origin "HEAD:${TARGET_GIT_BRANCH}"
                '''
            }
        }
    }
}

def buildCommitMessage(String operation, String physicalCluster, String logicalEnv, String connectorRef, String changeTicket) {
    def ticket = changeTicket?.trim() ? " [${changeTicket.trim()}]" : ''
    return "Kafka Connector ${operation}: ${physicalCluster}/${logicalEnv}/${connectorRef}${ticket}"
}

return this
