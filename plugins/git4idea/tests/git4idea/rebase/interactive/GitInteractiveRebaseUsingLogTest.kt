// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.rebase.interactive

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.vcs.log.VcsCommitMetadata
import com.intellij.vcs.log.data.DataPackChangeListener
import com.intellij.vcs.log.data.VcsLogData
import com.intellij.vcs.log.impl.FatalErrorHandler
import git4idea.branch.GitRebaseParams
import git4idea.rebase.GitInteractiveRebaseEditorHandler
import git4idea.rebase.GitRebaseEntry
import git4idea.rebase.GitRebaseUtils
import git4idea.test.GitSingleRepoTest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class GitInteractiveRebaseUsingLogTest : GitSingleRepoTest() {
  companion object {
    private val LOG = logger<GitInteractiveRebaseUsingLogTest>()
  }

  private lateinit var logData: VcsLogData

  override fun setUp() {
    super.setUp()
    logData = VcsLogData(project, mapOf(repo.root to logProvider), object : FatalErrorHandler {
      override fun consume(source: Any?, throwable: Throwable) {
        LOG.error(throwable)
      }

      override fun displayFatalErrorMessage(message: String) {
        LOG.error(message)
      }
    }, testRootDisposable)
  }

  fun `test simple commits`() {
    val commit0 = file("firstFile.txt").create("").addCommit("0").details()
    build {
      1()
      2()
      3()
      4()
    }
    checkEntriesGeneration(commit0)
  }

  fun `test commit with trailing spaces`() {
    checkEntryGenerationForSingleCommitWithMessage {
      "Subject with trailing spaces  \n\nBody \nwith \nspaces."
    }
  }

  fun `test commit with tag in subject`() {
    checkEntryGenerationForSingleCommitWithMessage {
      "Subject with #tag trailing spaces"
    }
  }

  fun `test commit with huge length`() {
    checkEntryGenerationForSingleCommitWithMessage {
      buildString {
        repeat(1000) {
          append('a')
        }
      }
    }
  }

  fun `test rebase with merge commit`() {
    val firstFile = "firstFile.txt"
    val commit0 = file(firstFile).create("").addCommit("0").details()
    build {
      master {
        1()
        2()
      }
      feature {
        3()
        4()
      }
      master {
        5()
        6()
      }
    }
    git("checkout master")
    git("merge feature", true)
    build {
      master {
        7()
        8()
      }
    }

    repo.update()
    assertExceptionDuringEntriesGeneration(commit0, CantRebaseUsingLogException.Reason.MERGE) {
      "We shouldn't generate entries if merge commit between HEAD and Rebase Base. Generated entries: $it"
    }
  }

  fun `test rebase with squash commit`() {
    val firstFile = "firstFile.txt"
    val commit0 = file(firstFile).create("").addCommit("0").details()
    build {
      master {
        1(commitMessage = "commit1")
        2(commitMessage = "commit2")
        3(commitMessage = "fixup! commit2")
        4(commitMessage = "commit3")
      }
    }
    assertExceptionDuringEntriesGeneration(commit0, CantRebaseUsingLogException.Reason.FIXUP_SQUASH) {
      "We shouldn't generate entries if squash!/fixup! prefix used. Generated entries: $it"
    }
  }

  private fun refreshLogAndWait() {
    val logWaiter = CompletableFuture<VcsLogData>()
    val dataPackChangeListener = DataPackChangeListener { newDataPack ->
      if (newDataPack.isFull) {
        logWaiter.complete(logData)
      }
    }
    logData.addDataPackChangeListener(dataPackChangeListener)
    logData.refresh(listOf(repo.root))
    try {
      logWaiter.get(5, TimeUnit.SECONDS)
    }
    catch (e: Exception) {
      fail(e.message)
    }
    finally {
      logData.removeDataPackChangeListener(dataPackChangeListener)
    }
  }

  private fun getRebaseEntriesUsingGit(commit: VcsCommitMetadata): List<GitRebaseEntry> {
    lateinit var entriesGeneratedUsingGit: List<GitRebaseEntry>
    val editorHandler = object : GitInteractiveRebaseEditorHandler(project, repo.root) {
      override fun collectNewEntries(entries: List<GitRebaseEntry>): List<GitRebaseEntry> {
        entriesGeneratedUsingGit = entries
        return entries
      }
    }
    val params = GitRebaseParams.editCommits(repo.vcs.version, commit.parents.first().asString(), editorHandler, false)
    GitRebaseUtils.rebase(repo.project, listOf(repo), params, EmptyProgressIndicator())
    return entriesGeneratedUsingGit
  }

  private fun checkEntriesGeneration(commit: VcsCommitMetadata) {
    refreshLogAndWait()
    val entriesGeneratedUsingLog = getEntriesUsingLog(repo, commit, logData)
    val entriesGeneratedUsingGit = getRebaseEntriesUsingGit(commit)
    assertTrue(entriesGeneratedUsingGit.isNotEmpty() && entriesGeneratedUsingLog.isNotEmpty())
    entriesGeneratedUsingLog.forEachIndexed { i, generatedEntry ->
      val realEntry = entriesGeneratedUsingGit[i]
      assertTrue("Generated entry: $generatedEntry, Real entry: $realEntry", generatedEntry.equalsWithReal(realEntry))
    }
  }

  private fun checkEntryGenerationForSingleCommitWithMessage(message: () -> String) {
    val commit = file("firstFile.txt").create("").addCommit(message()).details()
    checkEntriesGeneration(commit)
  }

  private fun assertExceptionDuringEntriesGeneration(
    commit: VcsCommitMetadata,
    reason: CantRebaseUsingLogException.Reason,
    failMessage: (entries: List<GitRebaseEntry>) -> String
  ) {
    refreshLogAndWait()
    try {
      val entries = getEntriesUsingLog(repo, commit, logData)
      fail(failMessage(entries))
    }
    catch (e: CantRebaseUsingLogException) {
      assertEquals(reason, e.reason)
    }
  }
}