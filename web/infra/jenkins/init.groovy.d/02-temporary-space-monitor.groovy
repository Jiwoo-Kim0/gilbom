import hudson.model.ComputerSet
import hudson.node_monitors.TemporarySpaceMonitor

def minimumFreeSpace = "64MB"
def warningFreeSpace = "128MB"
def monitors = ComputerSet.getMonitors()
def current = monitors.find {
    it instanceof TemporarySpaceMonitor
}

if (current == null
        || current.freeSpaceThreshold != minimumFreeSpace
        || current.freeSpaceWarningThreshold != warningFreeSpace
        || current.ignored) {
    def replacement = new TemporarySpaceMonitor(minimumFreeSpace)
    replacement.setFreeSpaceWarningThreshold(warningFreeSpace)
    replacement.setIgnored(false)

    if (current != null) {
        monitors.remove(current)
    }
    monitors.add(replacement)
}
