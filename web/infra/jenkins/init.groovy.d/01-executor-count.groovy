import jenkins.model.Jenkins

Jenkins instance = Jenkins.get()

if (instance.getNumExecutors() != 1) {
    instance.setNumExecutors(1)
    instance.save()
}

println("Gilbom Jenkins executor count: ${instance.getNumExecutors()}")
