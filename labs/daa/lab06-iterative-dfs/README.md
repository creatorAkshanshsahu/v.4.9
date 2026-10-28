# DAA Lab 6 — Iterative Depth First Search (DFS)

| | |
|---|---|
| **Subject** | Design and Analysis of Algorithms — CODE-CS201 / CS261 |
| **Institute** | IIIT Vadodara |
| **Name** | Ananya Arele |
| **Roll number** | 202504521007 |
| **Batch** | 2025 |
| **Assignment** | Implement the iterative version of the DFS algorithm |

---

## 1. Problem statement

Implement the iterative version of the DFS algorithm.

* **Input** — a graph stored as an **adjacency list**, and a **start node**
  (the vertex the traversal has to begin from).
* **Output** — the vertices of the graph in the order in which the depth first
  search visits them.

The recursive version of DFS is easy to write but it uses the call stack of
the process: every visited vertex costs one activation record, so a graph that
is deeper than the available call stack makes the program crash with a
segmentation fault. The iterative version keeps the "pending" vertices in an
explicit stack that lives on the heap, therefore the depth of the graph no
longer depends on the size of the call stack.

The whole program is in [`dfs.c`](dfs.c); the verified terminal session is in
[`output.txt`](output.txt) and the automated tests are in
[`tests/test_iterative_dfs.py`](tests/test_iterative_dfs.py).

---

## 2. Algorithm

```
Graph      : adjacency list representation
Start node : the vertex the traversal begins from

1.  Create an empty stack S and a visited array (all vertices unvisited).
2.  Push the start vertex onto S and mark it visited.
3.  While S is not empty:
4.      take the top frame F of S
5.      if F has no neighbour left to examine:
6.          pop F from S                 // "return" from the recursive call
7.      else:
8.          take the next neighbour w of F and move F past it
9.          if w is not visited:
10.             mark w visited and visit it (print it)
11.             push the frame (w, first neighbour of w) onto S
```

Steps 4–11 are the iterative form of the recursive function
`dfs(v) { visit(v); for each neighbour w of v: if not visited(w): dfs(w); }`.
A *frame* remembers which neighbour has to be examined next, which is what a
recursive call remembers in its return address and its loop counter.

### 2.1 The variant written in the lab notebook

The notebook of the lab record contains the classic textbook version:

```
1.  Initialize an empty stack.
2.  Initialize a visited set/array (all elements set to unvisited).
3.  Push the start node onto the stack.
4.  While stack is not empty:
5.      current node = stack.pop()
6.      If current node is not visited:
7.          Mark current node as visited.
8.          Process (visit) current node (e.g., print it).
9.      For each neighbour in Graph[current node]:
10.         If neighbour is not visited:
11.             Push neighbour onto the stack.
```

This version is implemented in the program as `--variant notebook` (function
`dfsNotebookVariant`). It is a valid depth first search, but it has two
drawbacks that the frame based version does not have:

* a vertex can be pushed on the stack several times, because the vertex is
  marked as visited only when it is popped;
* the visit order is **not** the order of the recursive DFS. Pushing all
  neighbours of a vertex at once explores them in the reverse order, and the
  vertex that was pushed first is visited last.

For the sample graph of the assignment the notebook version prints
`0 2 5 1 4 3`, while the recursive DFS prints `0 1 3 4 2 5`. The final
program therefore uses the frame based version, which prints the recursive
order for **every** graph (verified against the recursive implementation on
200 randomly generated graphs, see section 8).

---

## 3. Data structures

| Structure | Representation |
|---|---|
| Graph | `struct Graph` with one adjacency list (`struct Node *` chain) per vertex; the edges of a vertex are kept in the order in which they were inserted |
| Explicit stack | `struct FrameStack`, a dynamically growing array of frames (`vertex` + pointer to the next neighbour to examine) on the heap |
| Visited set | `bool visited[]`, one flag per vertex |

Both the stack and the visited array are allocated with `malloc`/`calloc` and
released with `free`, so the program does not leak memory.

---

## 4. Program

The complete program is [`dfs.c`](dfs.c) (874 lines, C11, no external
dependency). The core of the traversal is:

```c
static void dfsIterativeFrom(const Graph *graph, int start, bool *visited, int *order, int *count)
{
    FrameStack *stack = frameStackCreate();
    if (stack == NULL) {
        return;
    }
    visited[start] = true;
    order[(*count)++] = start;
    frameStackPush(stack, start, graph->adjLists[start]);

    while (!frameStackIsEmpty(stack)) {
        Frame *top = frameStackPeek(stack);
        int neighbour;

        if (top->next == NULL) {
            /* every neighbour of this vertex has been examined: return */
            frameStackPop(stack);
            continue;
        }
        neighbour = top->next->vertex;
        top->next = top->next->next;

        if (!visited[neighbour]) {
            visited[neighbour] = true;
            order[(*count)++] = neighbour;
            frameStackPush(stack, neighbour, graph->adjLists[neighbour]);
        }
    }
    frameStackFree(stack);
}
```

The recursive reference implementation used for the verification:

```c
static void dfsRecursiveVisit(const Graph *graph, int vertex, bool *visited, int *order, int *count)
{
    Node *node;

    visited[vertex] = true;
    order[(*count)++] = vertex;

    for (node = graph->adjLists[vertex]; node != NULL; node = node->next) {
        if (!visited[node->vertex]) {
            dfsRecursiveVisit(graph, node->vertex, visited, order, count);
        }
    }
}
```

### 4.1 Command line

```
Usage: dfs [options]

  --start <vertex>   vertex the traversal starts from (default 0)
  --variant <name>   canonical (default), notebook or recursive
  --compare          run the recursive reference as well and report
                     whether both traversals agree
  --forest           traverse every connected component
  --show-graph       print the adjacency lists before the traversal
  --input <file>     read the graph from a file instead of the sample
  --stress <n>       traverse a path graph with n vertices to show that
                     the iterative version does not use the call stack
  -h, --help         show this help
```

Input file format: `V E` followed by `E` lines `u v` (undirected edges) and an
optional last line holding the start vertex.

---

## 5. Sample output

The graph of the assignment has six vertices (0 … 5) and the edges
`0-1, 0-2, 1-3, 1-4, 2-5`:

```
$ gcc -std=c11 -Wall -Wextra -pedantic -O2 dfs.c -o dfs
$ ./dfs
Iterative DFS traversal starting from vertex 0: 0 1 3 4 2 5
```

This is exactly the output that was produced by the first version of the
program that was submitted. The complete session, including the comparison
with the recursive version, is stored in [`output.txt`](output.txt).

---

## 6. Complexity analysis

| | Recursive DFS | Iterative DFS (this program) |
|---|---|---|
| Time | `O(V + E)` | `O(V + E)` — every vertex is visited once and every edge is examined twice (once per direction) |
| Auxiliary space | `O(V)` — the visited array plus `O(V)` activation records on the call stack | `O(V)` — the visited array plus at most one frame per visited vertex on the heap |
| Graph storage | `O(V + E)` | `O(V + E)` |
| Maximum depth | limited by the size of the call stack (≈ 8 MB by default) | limited by the available heap memory |

The two versions are therefore equal in asymptotic complexity; the iterative
version only replaces the call stack by an explicit stack on the heap.

### 6.1 Measured difference (this machine, gcc 12.2, x86-64, 8 MB stack)

`./dfs --stress <n>` builds the worst case for a recursive traversal — a path
graph `0-1-2-…-(n-1)`, where the recursion depth equals the number of
vertices — and runs the iterative version in the parent process and the
recursive version in a child process:

| Vertices | Iterative (heap stack) | Recursive (call stack) |
|---:|---|---|
| 100 000 | visited in 0.002 s | completed |
| 150 000 | visited in 0.003 s | completed |
| 200 000 | visited in 0.004 s | **crashed with signal 11 (stack overflow)** |
| 500 000 | visited in 0.015 s | **crashed with signal 11 (stack overflow)** |
| 1 000 000 | visited in 0.025 s | **crashed with signal 11 (stack overflow)** |

The recursive version survives roughly 1.7 · 10⁵ nested calls with an 8 MB
stack (≈ 48 bytes per activation record); the iterative version is limited by
the heap only.

---

## 7. Correctness argument

Let `visited` be the visited array and `S` the explicit stack.

1. **Every vertex is visited at most once.** A vertex is marked visited at the
   moment it is visited, and a vertex is visited only when it is unvisited
   (step 9 of the algorithm). Hence no vertex can appear twice in the output.
2. **Every reachable vertex is visited.** When the algorithm visits a vertex
   `v` it examines every neighbour of `v` one after the other (the frame of
   `v` is popped only after its last neighbour has been examined). Therefore
   every unvisited neighbour of `v` is visited, and by induction on the length
   of a path from the start vertex, every vertex that is reachable from the
   start vertex is visited.
3. **The traversal is a depth first traversal.** A vertex `w` is pushed onto
   the stack before the frames of the vertices that were pushed earlier are
   examined again, and the frame of `w` is popped only after all of its
   neighbours have been examined. The exploration therefore finishes the whole
   subtree of `w` before it continues with the remaining neighbours of the
   parent — exactly the behaviour of the recursive version.
4. **The order is the order of the recursive DFS.** The frame stores the
   neighbour that still has to be examined, i.e. the state of the loop of the
   recursive call. Both implementations therefore examine the neighbours of a
   vertex in the same order and visit the vertices in the same order; this is
   checked at run time by `--compare` and by the test suite.

---

## 8. Tests

```bash
cd labs/daa/lab06-iterative-dfs
make test          # or: python3 -B tests/test_iterative_dfs.py -v
```

The suite compiles the program twice — once with `-fsanitize=address,undefined`
and once optimised — and checks:

* the documented output of the sample graph (`0 1 3 4 2 5`);
* that the iterative and the recursive traversals agree;
* the visit order of the notebook variant;
* 200 randomly generated graphs against a recursive reference implementation
  written in Python (the random generator is seeded, so the run is
  reproducible);
* the DFS forest of a disconnected graph;
* self loops, a missing input file, a malformed input file, an unknown option
  and a start vertex outside the graph;
* that the iterative version survives a graph that is deeper than the call
  stack.

Result of the last run: **13 tests, all passing**, no warnings from the
compiler and no report from the sanitizers.

---

## 9. Review of the version that was submitted first

The first version of the program (`dfs.c`, ~134 lines) was working and it
printed the expected output, but the review of the code showed the following
problems. They are all fixed in the current version.

| # | Problem in the first version | Consequence | Fix |
|---|---|---|---|
| 1 | The stack was a fixed array of `MAX_NODES` (100) entries | graphs with more than 100 pending vertices could not be traversed completely | the stack grows dynamically (`realloc`) and has no limit |
| 2 | `if (stack->top == MAX_NODES - 1) { "Stack overflow"; return; }` | the last free slot was never used, and a rejected push silently dropped a vertex | the overflow check was replaced by the dynamic stack |
| 3 | A vertex was marked visited when it was **popped** | the same vertex could be pushed several times, the stack grew unnecessarily | the frame based version marks a vertex when it is visited |
| 4 | The neighbours were pushed in adjacency order | the visit order was not the order of the recursive DFS (the code comment already hinted at this) | the frames remember the position inside the adjacency list, the order is now always the recursive one |
| 5 | The adjacency lists were never released | memory leak (harmless for the demo, wrong for a library) | `graphFree()` releases every node and the list array |
| 6 | `pop()` returned `-1` for an empty stack | `-1` cannot be distinguished from a vertex id | the stack is only popped when it is not empty (`frameStackIsEmpty`) |
| 7 | Only the component of the start vertex was traversed | the vertices of the other components were silently ignored | `--forest` traverses every component |
| 8 | The result of `malloc` was never checked, the start vertex was never validated | a crash or an out of range access on invalid input | every allocation is checked and the input is validated (non zero exit status, message on `stderr`) |
| 9 | The graph was hard coded | the program could only be used for the example | `--input` reads any graph from a file, `--start` chooses the start vertex |

The output of the sample graph did not change: the first version printed
`Iterative DFS traversal starting from vertex 0: 0 1 3 4 2 5` and so does the
current one — but now the order is guaranteed to be the order of the recursive
DFS for every graph, not only for the example.

---

## 10. How to build and run

```bash
cd labs/daa/lab06-iterative-dfs

make                # gcc -std=c11 -Wall -Wextra -pedantic -O2 dfs.c -o dfs
make run            # run on the sample graph
make compare        # iterative vs. recursive
make test           # run the test suite
```

Without `make`:

```bash
gcc -std=c11 -Wall -Wextra -pedantic -O2 dfs.c -o dfs
./dfs --compare
```

The program also builds with `clang` and with the sanitizers:

```bash
clang -std=c11 -Wall -Wextra dfs.c -o dfs
gcc -g -fsanitize=address,undefined dfs.c -o dfs && ./dfs --compare
```

---

## 11. Files

| File | Content |
|---|---|
| [`dfs.c`](dfs.c) | the complete program (graph, explicit stack, three traversal variants, command line) |
| [`Makefile`](Makefile) | `make`, `make run`, `make compare`, `make forest`, `make stress`, `make test`, `make clean` |
| [`output.txt`](output.txt) | the verified terminal session |
| [`tests/test_iterative_dfs.py`](tests/test_iterative_dfs.py) | the automated test suite |
