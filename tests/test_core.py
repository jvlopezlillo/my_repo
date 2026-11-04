import os
import subprocess

import pytest

from gitree.git import Repo
from gitree.graph import build_graph


def sh(cwd, *args):
    env = {**os.environ, "GIT_AUTHOR_NAME": "T", "GIT_AUTHOR_EMAIL": "t@t", "GIT_COMMITTER_NAME": "T",
           "GIT_COMMITTER_EMAIL": "t@t"}
    subprocess.run(args, cwd=cwd, check=True, capture_output=True, env=env)


@pytest.fixture
def repo(tmp_path):
    p = str(tmp_path / "r")
    r = Repo.init(p)
    sh(p, "git", "config", "user.name", "T")
    sh(p, "git", "config", "user.email", "t@t")
    sh(p, "git", "checkout", "-q", "-b", "main")
    return r


def write(repo, name, text):
    with open(os.path.join(repo.path, name), "w") as fh:
        fh.write(text)


def test_status_stage_commit(repo):
    assert repo.status() == []
    write(repo, "a.txt", "hola\n")
    st = repo.status()
    assert st[0].untracked and st[0].unstaged and not st[0].staged
    repo.stage(["a.txt"])
    assert repo.status()[0].staged
    repo.unstage(["a.txt"])  # también en repo sin commits
    assert repo.status()[0].untracked
    repo.stage(["a.txt"])
    repo.commit("primero")
    assert repo.status() == []
    assert [c.subject for c in repo.log()] == ["primero"]


def test_branch_merge_graph(repo):
    write(repo, "a", "1\n"); repo.stage(["a"]); repo.commit("c1")
    repo.create_branch("feat")
    write(repo, "b", "2\n"); repo.stage(["b"]); repo.commit("c2")
    repo.checkout("main")
    write(repo, "c", "3\n"); repo.stage(["c"]); repo.commit("c3")
    repo.merge("feat")
    commits = repo.log()
    assert commits[0].subject.startswith("Merge")
    assert len(commits[0].parents) == 2
    rows, lanes = build_graph(commits)
    assert len(rows) == len(commits) and lanes >= 2
    assert any(r.name == "main" and r.is_head for r in commits[0].refs)
    info = repo.refs()
    assert {b.name for b in info.local} == {"main", "feat"}
    files = repo.commit_files(commits[1])
    assert files and files[0][0] == "A"


def test_hunk_patch_and_discard(repo):
    write(repo, "f", "".join(f"l{i}\n" for i in range(30)))
    repo.stage(["f"]); repo.commit("base")
    lines = [f"l{i}\n" for i in range(30)]
    lines[1] = "X\n"; lines[28] = "Y\n"
    write(repo, "f", "".join(lines))
    fs = repo.status()[0]
    diff = repo.diff_file(fs, staged=False)
    first, second = diff.split("\n@@", 1)[0], "@@" + diff.split("\n@@", 1)[1]
    hunks = second.split("\n@@")
    patch = first + "\n" + hunks[0] + "\n"
    repo.apply_patch(patch, cached=True)
    st = repo.status()[0]
    assert st.staged and st.unstaged
    assert "X" in repo.diff_file(st, staged=True) and "Y" not in repo.diff_file(st, staged=True)


def test_stash_and_tags(repo):
    write(repo, "a", "1\n"); repo.stage(["a"]); repo.commit("c1")
    write(repo, "a", "2\n")
    repo.stash_push("wip")
    assert repo.status() == [] and repo.stashes()[0].message.endswith("wip")
    repo.stash_pop("stash@{0}")
    assert repo.status()
    repo.create_tag("v1", message="rel")
    assert "v1" in repo.refs().tags
    repo.delete_tag("v1")


def test_push_pull_fetch(repo, tmp_path):
    write(repo, "a", "1\n"); repo.stage(["a"]); repo.commit("c1")
    bare = str(tmp_path / "bare.git")
    sh(str(tmp_path), "git", "init", "-q", "--bare", "--initial-branch=main", bare)
    repo.add_remote("origin", bare)
    repo.push()  # configura upstream automáticamente
    assert repo.refs().local[0].upstream == "origin/main"
    clone = Repo.clone(bare, str(tmp_path / "c"))
    sh(clone.path, "git", "config", "user.name", "T")
    sh(clone.path, "git", "config", "user.email", "t@t")
    write(clone, "b", "x\n"); clone.stage(["b"]); clone.commit("c2"); clone.push()
    repo.fetch()
    assert repo.refs().local[0].behind == 1
    repo.pull()
    assert repo.log()[0].subject == "c2"


def test_graph_single_root_commit():
    from gitree.git import Commit
    rows, lanes = build_graph([Commit("a" * 40, [], "A", "a@a", 0, "root")])
    assert lanes == 1 and rows[0].col == 0
