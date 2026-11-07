"""Check the README's offline link graph, including heading anchors, using only root."""

import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit


def prose(path):
    return re.sub(r"^```[^\n]*\n.*?^```[^\n]*$", "", path.read_text(), flags=re.M | re.S)


def anchors(text):
    result = set()
    counts = {}
    for heading in re.findall(r"^#{1,6} (.+)$", text, flags=re.M):
        slug = re.sub(r"[^\w\- ]", "", heading.lower()).replace(" ", "-")
        count = counts.get(slug, 0)
        counts[slug] = count + 1
        result.add(f"{slug}-{count}" if count else slug)
    return result


def check(root):
    pending = [root / "README.md"]
    visited = set()
    links = 0
    while pending:
        source = pending.pop()
        if source in visited:
            continue
        visited.add(source)
        text = prose(source)
        for target in re.findall(r"(?<!!)\[[^\]\n]+\]\(([^\s)]+)\)", text):
            url = urlsplit(target)
            if url.scheme or url.netloc:
                continue
            path = (source.parent / unquote(url.path)).resolve() if url.path else source
            if not path.is_relative_to(root) or not path.is_file():
                raise ValueError(f"{source.relative_to(root)}: unavailable offline link {target}")
            if url.fragment and unquote(url.fragment) not in anchors(prose(path)):
                raise ValueError(f"{source.relative_to(root)}: missing heading {target}")
            if path.suffix == ".md":
                pending.append(path)
            links += 1
    print(f"Offline documentation: {len(visited)} files, {links} local links passed.")


if __name__ == "__main__":
    try:
        check(Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve())
    except (OSError, ValueError) as error:
        sys.exit(str(error))
