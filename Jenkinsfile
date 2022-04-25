pipeline {
    agent any
    stages {
        stage('Compile') {
            steps {
                sh 'mvn clean install -Dmaven.javadoc.skip=true -DskipTests'
            }
        }
        stage('Test') {
            steps {
                sh 'docker pull linagora/tmail-backend:memory-branch-master'
                sh 'mvn -B surefire:test'
            }
            post {
                always {
                    deleteDir() /* clean up our workspace */
                }
            }
        }
    }
}