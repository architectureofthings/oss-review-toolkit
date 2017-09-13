package com.here.provenanceanalyzer.downloader

import java.io.File

val VERSION_CONTROL_SYSTEMS = listOf(
        Git,
        GitRepo
)

abstract class VersionControlSystem {

    /**
     * Use this VCS to download the source code from the specified URL.
     */
    abstract fun download(vcsUrl: String, vcsRevision: String?, vcsPath: String?, targetDir: File): Boolean

    /**
     * Return true if the provider name matches this VCS. For example for SVN it should return true on "svn",
     * "subversion", or any other spelling that clearly identifies SVN.
     */
    abstract fun isApplicableProvider(vcsProvider: String): Boolean

    /**
     * Return true if this VCS can download from the provided URL. Should only return true when it's almost unambiguous,
     * for example when the URL ends on ".git" for Git or contains "/svn/" for SVN, but not when it contains the string
     * "git" as this could also be part of the host or project names.
     */
    abstract fun isApplicableUrl(vcsUrl: String): Boolean

}