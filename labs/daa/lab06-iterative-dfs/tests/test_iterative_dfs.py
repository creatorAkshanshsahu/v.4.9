#!/usr/bin/env python3
"""Tests for the iterative DFS lab program (DAA Lab 6).

The suite compiles ``dfs.c`` twice - once with the address and undefined
behaviour sanitizers and once with optimisations - and then checks the
traversal of the sample graph, of hand written graphs and of a few hundred
randomly generated graphs against a recursive reference implementation.
"""

import os
import random
import subprocess
import tempfile
import unittest
from pathlib import Path

LAB_DIR = Path(__file__).resolve().parent.parent
SOURCE = LAB_DIR / "dfs.c"


def build(cc, output, extra=()):
    """Compiles the lab program and returns the path of the binary."""
    command = [cc, "-std=c11", "-Wall", "-Wextra", "-pedantic", "-o", str(output), str(SOURCE), *extra]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        raise AssertionError(f"compilation failed:\n{result.stderr}")
    if result.stderr.strip():
        raise AssertionError(f"the compiler reported warnings:\n{result.stderr}")
    return output


def run(binary, *args):
    return subprocess.run([str(binary), *args], capture_output=True, text=True)


def traversal_from(output):
    """Extracts the vertex sequence from a traversal line."""
    line = output.strip().splitlines()[-1]
    return [int(token) for token in line.split(":")[1].split()]


def reference_dfs(vertices, edges, start):
    """Recursive reference: neighbours are visited in insertion order."""
    adjacency = {vertex: [] for vertex in range(vertices)}
    for src, dest in edges:
        adjacency[src].append(dest)
        if src != dest:
            adjacency[dest].append(src)
    visited = set()
    order = []

    def visit(vertex):
        visited.add(vertex)
        order.append(vertex)
        for neighbour in adjacency[vertex]:
            if neighbour not in visited:
                visit(neighbour)

    visit(start)
    return order


class IterativeDfsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._directory = tempfile.TemporaryDirectory()
        cls.directory = Path(cls._directory.name)
        cls.binary = build(os.environ.get("CC", "gcc"), cls.directory / "dfs", ("-O2",))
        cls.sanitized = build(
            os.environ.get("CC", "gcc"),
            cls.directory / "dfs_sanitized",
            ("-g", "-fsanitize=address,undefined"),
        )

    @classmethod
    def tearDownClass(cls):
        cls._directory.cleanup()

    def write_graph(self, vertices, edges, start=None):
        path = self.directory / "graph.txt"
        lines = [f"{vertices} {len(edges)}"]
        lines += [f"{src} {dest}" for src, dest in edges]
        if start is not None:
            lines.append(str(start))
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        return path

    def test_sample_graph_prints_the_documented_order(self):
        result = run(self.binary)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            result.stdout,
            "Iterative DFS traversal starting from vertex 0: 0 1 3 4 2 5\n",
        )

    def test_sanitized_build_is_silent(self):
        result = run(self.sanitized, "--compare")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stderr, "")

    def test_iterative_and_recursive_traversals_agree(self):
        result = run(self.binary, "--compare")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Iterative and recursive traversals agree: yes", result.stdout)
        iterative, recursive = result.stdout.splitlines()[:2]
        self.assertEqual(traversal_from(iterative), traversal_from(recursive))
        self.assertEqual(traversal_from(iterative), [0, 1, 3, 4, 2, 5])

    def test_notebook_pseudo_code_variant(self):
        result = run(self.binary, "--variant", "notebook")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(traversal_from(result.stdout), [0, 2, 5, 1, 4, 3])

    def test_start_vertex_can_be_chosen(self):
        result = run(self.binary, "--start", "4")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(traversal_from(result.stdout), [4, 1, 0, 2, 5, 3])

    def test_forest_visits_every_component(self):
        path = self.write_graph(7, [(0, 1), (0, 2), (1, 3), (1, 4), (2, 5), (5, 6)])
        result = run(self.binary, "--input", str(path), "--forest", "--compare")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Iterative and recursive traversals agree: yes", result.stdout)
        self.assertEqual(sorted(traversal_from(result.stdout.splitlines()[0])), list(range(7)))

    def test_random_graphs_match_the_recursive_reference(self):
        generator = random.Random(20251026)
        for _ in range(200):
            vertices = generator.randint(2, 12)
            edges = [
                (generator.randrange(vertices), generator.randrange(vertices))
                for _ in range(generator.randint(0, 3 * vertices))
            ]
            start = generator.randrange(vertices)
            path = self.write_graph(vertices, edges, start)
            result = run(self.binary, "--input", str(path))
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(traversal_from(result.stdout), reference_dfs(vertices, edges, start))

    def test_self_loop_does_not_break_the_traversal(self):
        path = self.write_graph(3, [(0, 0), (0, 1), (1, 2)])
        result = run(self.binary, "--input", str(path))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(traversal_from(result.stdout), reference_dfs(3, [(0, 0), (0, 1), (1, 2)], 0))

    def test_missing_input_file_is_reported(self):
        result = run(self.binary, "--input", str(self.directory / "missing.txt"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Cannot open graph file", result.stderr)

    def test_malformed_input_file_is_reported(self):
        path = self.directory / "broken.txt"
        path.write_text("4 3\n0 1\n", encoding="utf-8")
        result = run(self.binary, "--input", str(path))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("endpoints", result.stderr)

    def test_start_vertex_outside_the_graph_is_rejected(self):
        result = run(self.binary, "--start", "99")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("outside the graph", result.stderr)

    def test_unknown_option_is_rejected(self):
        result = run(self.binary, "--nope")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Unknown option", result.stderr)

    def test_iterative_version_survives_a_graph_deeper_than_the_call_stack(self):
        result = run(self.binary, "--stress", "200000")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Iterative DFS (heap stack): visited 200000 vertices", result.stdout)
        self.assertIn("Recursive DFS (call stack): crashed with signal 11", result.stdout)


if __name__ == "__main__":
    unittest.main()
