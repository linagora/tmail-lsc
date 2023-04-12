pipeline {
    agent {
      label 'jdk17'
    }
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
        stage('Deliver Docker image') {
            when {
                anyOf {
                    branch 'main'
                    buildingTag()
                }
            }
            steps {
                script {
                    def lscImage = docker.image "linagora/tmail-lsc:latest"
                    docker.withRegistry('', 'dockerHub') {
                        lscImage.push()
                    }
                }
            }
        }
    }
}