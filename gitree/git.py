"""Capa fina sobre el binario ``git``. Sin dependencias de Qt."""
from __future__ import annotations

import os
import subprocess
from dataclasses import dataclass, field

_FS = "\x1f"
_RS = "\x1e"


class GitError(Exception):
    def __init__(self, message: str, returncode: int = 1):
        super().__init__(message)
        self.returncode = returncode


@dataclass
class Ref:
    name: str
    kind: str  # 'local' | 'remote' | 'tag' | 'head' (HEAD desacoplado)
    is_head: bool = False


@dataclass
class Commit:
    hash: str
    parents: list[str]
    author: str
    email: str
    date: int
    subject: str
    refs: list[Ref] = field(default_factory=list)
    wip: bool = False  # fila virtual «cambios sin confirmar»

    @property
    def short(self) -> str:
        return self.hash[:7]


@dataclass
class FileStatus:
    path: str
    index: str  # columna X de `status --porcelain`
    worktree: str  # columna Y
    orig: str | None = None

    @property
    def untracked(self) -> bool:
        return self.index == "?" and self.worktree == "?"

    @property
    def conflicted(self) -> bool:
        pair = self.index + self.worktree
        return pair in ("DD", "AU", "UD", "UA", "DU", "AA", "UU")

    @property
    def staged(self) -> bool:
        return not self.conflicted and self.index not in (" ", "?", "!")

    @property
    def unstaged(self) -> bool:
        return self.conflicted or self.worktree not in (" ", "!")


@dataclass
class Branch:
    name: str
    is_current: bool = False
    upstream: str = ""
    ahead: int = 0
    behind: int = 0
    gone: bool = False


@dataclass
class RefsInfo:
    local: list[Branch] = field(default_factory=list)
    remote: list[str] = field(default_factory=list)  # 'origin/main'
    tags: list[str] = field(default_factory=list)


@dataclass
class Stash:
    ref: str  # stash@{0}
    message: str


class Repo:
    def __init__(self, path: str):
        self.path = path

    # ------------------------------------------------------------------ core
    @staticmethod
    def discover(path: str) -> "Repo":
        path = os.path.abspath(os.path.expanduser(path))
        try:
            out = subprocess.run(
                ["git", "-C", path, "rev-parse", "--show-toplevel"],
                capture_output=True, text=True,
            )
        except FileNotFoundError as e:
            raise GitError("No se encontró el ejecutable «git». Instálalo primero.") from e
        if out.returncode != 0:
            raise GitError(f"«{path}» no es un repositorio Git.")
        return Repo(out.stdout.strip())

    @staticmethod
    def init(path: str) -> "Repo":
        os.makedirs(path, exist_ok=True)
        r = subprocess.run(["git", "-C", path, "init"], capture_output=True, text=True)
        if r.returncode != 0:
            raise GitError(r.stderr.strip())
        return Repo.discover(path)

    @staticmethod
    def clone(url: str, dest: str) -> "Repo":
        env = {**os.environ, "GIT_TERMINAL_PROMPT": "0"}
        r = subprocess.run(["git", "clone", url, dest], capture_output=True, text=True, env=env)
        if r.returncode != 0:
            raise GitError(r.stderr.strip() or r.stdout.strip())
        return Repo.discover(dest)

    def run(self, *args: str, input: str | None = None, check: bool = True) -> str:
        env = {**os.environ, "GIT_TERMINAL_PROMPT": "0", "GIT_EDITOR": "true",
               "GIT_MERGE_AUTOEDIT": "no"}
        proc = subprocess.run(
            ["git", "-C", self.path, *args],
            input=input.encode() if input is not None else None,
            capture_output=True, env=env,
        )
        out = proc.stdout.decode("utf-8", "replace")
        if check and proc.returncode != 0:
            err = proc.stderr.decode("utf-8", "replace").strip() or out.strip()
            raise GitError(err or f"git {' '.join(args)} falló", proc.returncode)
        return out

    @property
    def name(self) -> str:
        return os.path.basename(self.path.rstrip("/")) or self.path

    # ----------------------------------------------------------------- state
    def has_commits(self) -> bool:
        return self.run("rev-parse", "--verify", "-q", "HEAD", check=False).strip() != ""

    def head_hash(self) -> str | None:
        return self.run("rev-parse", "--verify", "-q", "HEAD", check=False).strip() or None

    def current_branch(self) -> str | None:
        out = self.run("symbolic-ref", "-q", "--short", "HEAD", check=False).strip()
        return out or None

    def remotes(self) -> list[str]:
        return [r for r in self.run("remote", check=False).splitlines() if r]

    def status(self) -> list[FileStatus]:
        out = self.run("status", "--porcelain=v1", "-z", "-uall")
        parts = out.split("\0")
        res: list[FileStatus] = []
        i = 0
        while i < len(parts):
            entry = parts[i]
            i += 1
            if len(entry) < 4:
                continue
            x, y, path = entry[0], entry[1], entry[3:]
            orig = None
            if x in "RC" or y in "RC":
                orig = parts[i] if i < len(parts) else None
                i += 1
            res.append(FileStatus(path, x, y, orig))
        return res

    def refs(self) -> RefsInfo:
        fmt = _FS.join(["%(refname)", "%(refname:short)", "%(HEAD)", "%(upstream:short)",
                        "%(upstream:track,nobracket)"])
        out = self.run("for-each-ref", f"--format={fmt}", "refs/heads", "refs/remotes", "refs/tags")
        info = RefsInfo()
        for line in out.splitlines():
            parts = line.split(_FS)
            if len(parts) < 5:
                continue
            full, short, head, upstream, track = parts[:5]
            if full.startswith("refs/heads/"):
                b = Branch(short, head == "*", upstream)
                for chunk in track.split(","):
                    chunk = chunk.strip()
                    if chunk.startswith("ahead "):
                        b.ahead = int(chunk[6:])
                    elif chunk.startswith("behind "):
                        b.behind = int(chunk[7:])
                    elif chunk == "gone":
                        b.gone = True
                info.local.append(b)
            elif full.startswith("refs/remotes/"):
                if not full.endswith("/HEAD"):
                    info.remote.append(short)
            elif full.startswith("refs/tags/"):
                info.tags.append(short)
        return info

    def stashes(self) -> list[Stash]:
        out = self.run("stash", "list", f"--format=%gd{_FS}%s", check=False)
        res = []
        for line in out.splitlines():
            ref, _, msg = line.partition(_FS)
            res.append(Stash(ref, msg))
        return res

    def merge_in_progress(self) -> bool:
        p = self.run("rev-parse", "--git-path", "MERGE_HEAD", check=False).strip()
        return bool(p) and os.path.exists(os.path.join(self.path, p))

    def merge_message(self) -> str:
        p = self.run("rev-parse", "--git-path", "MERGE_MSG", check=False).strip()
        full = os.path.join(self.path, p) if p else ""
        if full and os.path.exists(full):
            with open(full, encoding="utf-8", errors="replace") as fh:
                return "".join(l for l in fh if not l.startswith("#")).strip()
        return ""

    # --------------------------------------------------------------- history
    def log(self, all_refs: bool = True, limit: int = 3000) -> list[Commit]:
        fmt = _FS.join(["%H", "%P", "%an", "%ae", "%at", "%D", "%s"]) + _RS
        args = ["log", "--topo-order", f"-n{limit}", f"--format={fmt}"]
        args += ["--exclude=refs/stash", "--all"] if all_refs else ["HEAD"]
        out = self.run(*args, check=False)
        if not out:
            return []
        info = self.refs()
        remote_set = set(info.remote)
        commits: list[Commit] = []
        for rec in out.split(_RS):
            rec = rec.strip("\n")
            if not rec:
                continue
            h, parents, an, ae, at, deco, subj = rec.split(_FS, 6)
            refs: list[Ref] = []
            for d in filter(None, (x.strip() for x in deco.split(","))):
                if d.startswith("tag: "):
                    refs.append(Ref(d[5:], "tag"))
                elif d.startswith("HEAD -> "):
                    refs.append(Ref(d[8:], "local", True))
                elif d == "HEAD":
                    refs.append(Ref("HEAD", "head", True))
                elif d in remote_set:
                    refs.append(Ref(d, "remote"))
                elif d.endswith("/HEAD"):
                    continue
                else:
                    refs.append(Ref(d, "local"))
            commits.append(Commit(h, parents.split(), an, ae, int(at or 0), subj, refs))
        return commits

    def commit_message(self, rev: str) -> str:
        return self.run("show", "-s", "--format=%B", rev, check=False).strip()

    def commit_files(self, c: Commit) -> list[tuple[str, str, str | None]]:
        """Devuelve [(estado, ruta, ruta_origen)] contra el primer padre."""
        if c.parents:
            out = self.run("diff", "-M", "--name-status", "-z", c.parents[0], c.hash)
        else:  # commit raíz
            out = self.run("diff-tree", "--root", "-r", "-M", "--no-commit-id", "--name-status", "-z", c.hash)
        parts = out.split("\0")
        res, i = [], 0
        while i < len(parts) and parts[i]:
            st = parts[i]
            if st[0] in "RC":
                res.append((st[0], parts[i + 2], parts[i + 1]))
                i += 3
            else:
                res.append((st[0], parts[i + 1], None))
                i += 2
        return res

    def commit_file_diff(self, c: Commit, path: str, orig: str | None = None) -> str:
        paths = [orig, path] if orig else [path]
        if c.parents:
            return self.run("diff", "-M", c.parents[0], c.hash, "--", *paths, check=False)
        return self.run("show", "--format=", "-M", c.hash, "--", *paths, check=False)

    # --------------------------------------------------------- working copy
    def diff_file(self, fs: FileStatus, staged: bool) -> str:
        if fs.untracked:
            return self.run("diff", "--no-index", "--", "/dev/null", fs.path, check=False)
        if staged:
            return self.run("diff", "--cached", "-M", "--", *filter(None, [fs.orig, fs.path]), check=False)
        return self.run("diff", "--", fs.path, check=False)

    def stage(self, paths: list[str]) -> None:
        self.run("add", "-A", "--", *paths)

    def unstage(self, paths: list[str]) -> None:
        self.run("reset", "-q", "--", *paths)

    def discard(self, fs: FileStatus) -> None:
        if fs.untracked:
            self.run("clean", "-fdq", "--", fs.path)
        else:
            self.run("checkout", "--", fs.path)

    def resolve_with(self, path: str, side: str) -> None:
        self.run("checkout", f"--{side}", "--", path)
        self.run("add", "--", path)

    def apply_patch(self, patch: str, cached: bool = False, reverse: bool = False) -> None:
        args = ["apply", "--recount"]
        if cached:
            args.append("--cached")
        if reverse:
            args.append("-R")
        self.run(*args, "-", input=patch)

    def add_to_gitignore(self, pattern: str) -> None:
        p = os.path.join(self.path, ".gitignore")
        existing = ""
        if os.path.exists(p):
            with open(p, encoding="utf-8") as fh:
                existing = fh.read()
        if pattern in existing.splitlines():
            return
        with open(p, "a", encoding="utf-8") as fh:
            if existing and not existing.endswith("\n"):
                fh.write("\n")
            fh.write(pattern + "\n")

    def commit(self, message: str, amend: bool = False) -> None:
        args = ["commit"]
        if amend:
            args.append("--amend")
        if message.strip():
            args += ["-F", "-"]
            self.run(*args, input=message)
        else:
            self.run(*args, "--no-edit")

    # -------------------------------------------------------------- branches
    def checkout(self, ref: str) -> None:
        self.run("checkout", ref)

    def checkout_remote(self, remote_branch: str) -> None:
        local = remote_branch.split("/", 1)[1]
        if any(b.name == local for b in self.refs().local):
            self.run("checkout", local)
        else:
            self.run("checkout", "--track", remote_branch)

    def create_branch(self, name: str, start: str | None = None, checkout: bool = True) -> None:
        if checkout:
            self.run("checkout", "-b", name, *( [start] if start else []))
        else:
            self.run("branch", name, *( [start] if start else []))

    def delete_branch(self, name: str, force: bool = False) -> None:
        self.run("branch", "-D" if force else "-d", name)

    def rename_branch(self, old: str, new: str) -> None:
        self.run("branch", "-m", old, new)

    def delete_remote_branch(self, remote_branch: str) -> None:
        remote, name = remote_branch.split("/", 1)
        self.run("push", remote, "--delete", name)

    def merge(self, ref: str) -> str:
        return self.run("merge", "--no-edit", ref)

    def merge_abort(self) -> None:
        self.run("merge", "--abort")

    def create_tag(self, name: str, rev: str = "HEAD", message: str = "") -> None:
        if message:
            self.run("tag", "-a", name, "-m", message, rev)
        else:
            self.run("tag", name, rev)

    def delete_tag(self, name: str) -> None:
        self.run("tag", "-d", name)

    def cherry_pick(self, rev: str) -> None:
        self.run("cherry-pick", rev)

    def revert(self, rev: str) -> None:
        self.run("revert", "--no-edit", rev)

    def reset(self, rev: str, mode: str) -> None:
        self.run("reset", f"--{mode}", rev)

    # ---------------------------------------------------------------- stash
    def stash_push(self, message: str = "") -> None:
        args = ["stash", "push", "-u"]
        if message:
            args += ["-m", message]
        self.run(*args)

    def stash_apply(self, ref: str) -> None:
        self.run("stash", "apply", ref)

    def stash_pop(self, ref: str) -> None:
        self.run("stash", "pop", ref)

    def stash_drop(self, ref: str) -> None:
        self.run("stash", "drop", ref)

    # --------------------------------------------------------------- remotes
    def fetch(self) -> str:
        return self.run("fetch", "--all", "--prune", "--tags")

    def pull(self, rebase: bool = False) -> str:
        return self.run("pull", "--rebase" if rebase else "--no-rebase", *([] if rebase else ["--no-edit"]))

    def push(self, tags: bool = False) -> str:
        branch = self.current_branch()
        info = next((b for b in self.refs().local if b.name == branch), None)
        args = ["push"]
        if tags:
            args.append("--follow-tags")
        if branch and info and not info.upstream:
            remotes = self.remotes()
            if not remotes:
                raise GitError("Este repositorio no tiene remotos configurados.")
            remote = "origin" if "origin" in remotes else remotes[0]
            args += ["-u", remote, branch]
        return self.run(*args)

    def push_tag(self, tag: str) -> str:
        remotes = self.remotes()
        if not remotes:
            raise GitError("Este repositorio no tiene remotos configurados.")
        return self.run("push", "origin" if "origin" in remotes else remotes[0], tag)

    def add_remote(self, name: str, url: str) -> None:
        self.run("remote", "add", name, url)
