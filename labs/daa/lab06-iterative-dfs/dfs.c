/*
 * Design and Analysis of Algorithms - Lab 6
 * "Implement the iterative version of the DFS algorithm."
 *
 * Student : Ananya Arele (202504521007)
 * Subject : CODE-CS201 / CS261, batch 2025
 * Build   : gcc -std=c11 -Wall -Wextra -pedantic dfs.c -o dfs
 *
 * The graph is stored as an adjacency list and the traversal is driven by an
 * explicit stack that lives on the heap.  Because no C function call is used
 * for the traversal, the algorithm is not limited by the size of the call
 * stack of the machine: graphs that are far deeper than the recursion limit
 * (a path graph with millions of vertices, for example) can still be walked.
 *
 * Three traversal flavours are provided:
 *
 *   canonical  (default) the iterative DFS used in the report.  The explicit
 *              stack holds one frame per vertex that is being explored, so
 *              the traversal produces exactly the same visit order as the
 *              recursive version.
 *   notebook   the literal version of the pseudo code written in the lab
 *              notebook: the vertex is marked when it is popped and the
 *              neighbours are pushed in adjacency order.
 *   recursive  the plain recursive reference implementation, kept so that the
 *              iterative result can be verified against it (--compare).
 */

#if defined(__unix__) && !defined(_POSIX_C_SOURCE)
#define _POSIX_C_SOURCE 200809L /* clock_gettime() */
#endif

#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#ifdef __unix__
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#endif

/* ------------------------------------------------------------------ */
/*  Graph - adjacency list representation                               */
/* ------------------------------------------------------------------ */

/* A node of an adjacency list: one neighbour of a vertex. */
typedef struct Node {
    int vertex;
    struct Node *next;
} Node;

typedef struct {
    int numVertices;
    Node **adjLists; /* adjLists[v] is the list of the neighbours of v */
} Graph;

static Graph *graphCreate(int numVertices)
{
    Graph *graph;

    if (numVertices <= 0) {
        return NULL;
    }
    graph = malloc(sizeof *graph);
    if (graph == NULL) {
        return NULL;
    }
    graph->numVertices = numVertices;
    graph->adjLists = calloc((size_t)numVertices, sizeof *graph->adjLists);
    if (graph->adjLists == NULL) {
        free(graph);
        return NULL;
    }
    return graph;
}

/*
 * Appends `dest` to the adjacency list of `src`.  The edge is appended at the
 * tail so that the adjacency list keeps the order in which the edges were
 * inserted - that keeps the traversal order predictable and reproducible.
 */
static void graphAddDirectedEdge(Graph *graph, int src, int dest)
{
    Node *node;
    Node *tail;

    if (src < 0 || src >= graph->numVertices || dest < 0 || dest >= graph->numVertices) {
        return;
    }
    node = malloc(sizeof *node);
    if (node == NULL) {
        return;
    }
    node->vertex = dest;
    node->next = NULL;

    if (graph->adjLists[src] == NULL) {
        graph->adjLists[src] = node;
        return;
    }
    tail = graph->adjLists[src];
    while (tail->next != NULL) {
        tail = tail->next;
    }
    tail->next = node;
}

/* Adds an undirected edge: both adjacency lists receive the opposite end. */
static void graphAddEdge(Graph *graph, int src, int dest)
{
    graphAddDirectedEdge(graph, src, dest);
    if (src != dest) {
        graphAddDirectedEdge(graph, dest, src);
    }
}

static void graphFree(Graph *graph)
{
    int v;

    if (graph == NULL) {
        return;
    }
    for (v = 0; v < graph->numVertices; v++) {
        Node *node = graph->adjLists[v];
        while (node != NULL) {
            Node *next = node->next;
            free(node);
            node = next;
        }
    }
    free(graph->adjLists);
    free(graph);
}

static void graphPrint(const Graph *graph)
{
    int v;

    printf("Graph (adjacency lists):\n");
    for (v = 0; v < graph->numVertices; v++) {
        Node *node;
        printf("  %d ->", v);
        for (node = graph->adjLists[v]; node != NULL; node = node->next) {
            printf(" %d", node->vertex);
        }
        printf("\n");
    }
}

/* ------------------------------------------------------------------ */
/*  Explicit stack (heap allocated, grows on demand)                   */
/* ------------------------------------------------------------------ */

typedef struct {
    int *items;
    int size;
    int capacity;
} Stack;

static Stack *stackCreate(void)
{
    Stack *stack = malloc(sizeof *stack);

    if (stack == NULL) {
        return NULL;
    }
    stack->items = malloc(8 * sizeof *stack->items);
    if (stack->items == NULL) {
        free(stack);
        return NULL;
    }
    stack->size = 0;
    stack->capacity = 8;
    return stack;
}

static bool stackPush(Stack *stack, int value)
{
    if (stack->size == stack->capacity) {
        int capacity = stack->capacity * 2;
        int *items = realloc(stack->items, (size_t)capacity * sizeof *items);

        if (items == NULL) {
            return false;
        }
        stack->items = items;
        stack->capacity = capacity;
    }
    stack->items[stack->size++] = value;
    return true;
}

static bool stackIsEmpty(const Stack *stack)
{
    return stack->size == 0;
}

static int stackPop(Stack *stack)
{
    if (stack->size == 0) {
        return -1;
    }
    return stack->items[--stack->size];
}

static void stackFree(Stack *stack)
{
    if (stack == NULL) {
        return;
    }
    free(stack->items);
    free(stack);
}

/* ------------------------------------------------------------------ */
/*  Explicit stack of frames (one frame per vertex being explored)     */
/* ------------------------------------------------------------------ */

/*
 * A frame is the iterative counterpart of an activation record of a
 * recursive call: it stores the vertex that is being explored and the
 * neighbour that has to be examined next.
 */
typedef struct {
    int vertex;
    Node *next; /* the neighbour that still has to be examined */
} Frame;

typedef struct {
    Frame *items;
    int size;
    int capacity;
} FrameStack;

static FrameStack *frameStackCreate(void)
{
    FrameStack *stack = malloc(sizeof *stack);

    if (stack == NULL) {
        return NULL;
    }
    stack->items = malloc(8 * sizeof *stack->items);
    if (stack->items == NULL) {
        free(stack);
        return NULL;
    }
    stack->size = 0;
    stack->capacity = 8;
    return stack;
}

static bool frameStackPush(FrameStack *stack, int vertex, Node *next)
{
    if (stack->size == stack->capacity) {
        int capacity = stack->capacity * 2;
        Frame *items = realloc(stack->items, (size_t)capacity * sizeof *items);

        if (items == NULL) {
            return false;
        }
        stack->items = items;
        stack->capacity = capacity;
    }
    stack->items[stack->size].vertex = vertex;
    stack->items[stack->size].next = next;
    stack->size++;
    return true;
}

static bool frameStackIsEmpty(const FrameStack *stack)
{
    return stack->size == 0;
}

static Frame *frameStackPeek(FrameStack *stack)
{
    return &stack->items[stack->size - 1];
}

static void frameStackPop(FrameStack *stack)
{
    stack->size--;
}

static void frameStackFree(FrameStack *stack)
{
    if (stack == NULL) {
        return;
    }
    free(stack->items);
    free(stack);
}

/* ------------------------------------------------------------------ */
/*  Traversal                                                          */
/* ------------------------------------------------------------------ */

/*
 * Canonical iterative DFS.
 *
 * The explicit stack holds one *frame* per vertex that is currently being
 * explored: the frame stores the vertex and the neighbour that has to be
 * examined next.  A frame is removed from the stack only when all of its
 * neighbours have been examined, which reproduces the "return" of a recursive
 * call.  Because the frames remember where the recursion stopped, the visit
 * order is exactly the order of the recursive version - a plain stack of
 * vertices cannot guarantee that (see dfsNotebookVariant below).
 */
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

/* Iterative DFS over a single connected component (the one holding `start`). */
static bool dfsIterative(const Graph *graph, int start, int *order, int *count)
{
    bool *visited = calloc((size_t)graph->numVertices, sizeof *visited);

    if (visited == NULL) {
        return false;
    }
    dfsIterativeFrom(graph, start, visited, order, count);
    free(visited);
    return true;
}

/*
 * Iterative DFS over every connected component of the graph (a DFS forest).
 * The components are visited in increasing order of their smallest vertex.
 */
static bool dfsForest(const Graph *graph, int *order, int *count)
{
    bool *visited = calloc((size_t)graph->numVertices, sizeof *visited);
    int v;

    if (visited == NULL) {
        return false;
    }
    for (v = 0; v < graph->numVertices; v++) {
        if (!visited[v]) {
            dfsIterativeFrom(graph, v, visited, order, count);
        }
    }
    free(visited);
    return true;
}

/*
 * The literal version of the pseudo code written in the lab notebook: the
 * popped vertex is marked visited at that moment and the neighbours are
 * pushed in adjacency order.  It is a valid depth first search and it visits
 * every reachable vertex exactly once, but the visit order differs from the
 * recursive version (a vertex can even be pushed several times) and the
 * traversal is not guaranteed to follow the depth first order of the
 * textbook.  It is kept here because it is the algorithm that was written in
 * the lab notebook.
 */
static bool dfsNotebookVariant(const Graph *graph, int start, int *order, int *count)
{
    bool *visited;
    Stack *stack = stackCreate();

    if (stack == NULL) {
        return false;
    }
    visited = calloc((size_t)graph->numVertices, sizeof *visited);
    if (visited == NULL) {
        stackFree(stack);
        return false;
    }

    stackPush(stack, start);
    while (!stackIsEmpty(stack)) {
        int current = stackPop(stack);
        Node *node;

        if (visited[current]) {
            continue;
        }
        visited[current] = true;
        order[(*count)++] = current;

        for (node = graph->adjLists[current]; node != NULL; node = node->next) {
            if (!visited[node->vertex]) {
                stackPush(stack, node->vertex);
            }
        }
    }

    free(visited);
    stackFree(stack);
    return true;
}

/* Plain recursive reference implementation (verification only). */
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

static bool dfsRecursive(const Graph *graph, int start, int *order, int *count)
{
    bool *visited = calloc((size_t)graph->numVertices, sizeof *visited);

    if (visited == NULL) {
        return false;
    }
    dfsRecursiveVisit(graph, start, visited, order, count);
    free(visited);
    return true;
}

/* Recursive reference implementation over every connected component. */
static bool dfsRecursiveForest(const Graph *graph, int *order, int *count)
{
    bool *visited = calloc((size_t)graph->numVertices, sizeof *visited);
    int v;

    if (visited == NULL) {
        return false;
    }
    for (v = 0; v < graph->numVertices; v++) {
        if (!visited[v]) {
            dfsRecursiveVisit(graph, v, visited, order, count);
        }
    }
    free(visited);
    return true;
}

/* ------------------------------------------------------------------ */
/*  Reporting helpers                                                  */
/* ------------------------------------------------------------------ */

/* Prints "<label>: v1 v2 v3" on a single line. */
static void printOrder(const char *label, const int *order, int count)
{
    int i;

    printf("%s: ", label);
    for (i = 0; i < count; i++) {
        printf("%s%d", (i == 0) ? "" : " ", order[i]);
    }
    printf("\n");
}

/* ------------------------------------------------------------------ */
/*  Command line handling                                              */
/* ------------------------------------------------------------------ */

typedef enum {
    VARIANT_CANONICAL,
    VARIANT_NOTEBOOK,
    VARIANT_RECURSIVE
} Variant;

typedef struct {
    int start;
    bool startGiven;
    Variant variant;
    bool compare;
    bool forest;
    bool showGraph;
    const char *inputPath;
    long stressVertices;
} Options;

static void printUsage(const char *program)
{
    printf("Usage: %s [options]\n", program);
    printf("\n");
    printf("  --start <vertex>   vertex the traversal starts from (default 0)\n");
    printf("  --variant <name>   canonical (default), notebook or recursive\n");
    printf("  --compare          run the recursive reference as well and report\n");
    printf("                     whether both traversals agree\n");
    printf("  --forest           traverse every connected component\n");
    printf("  --show-graph       print the adjacency lists before the traversal\n");
    printf("  --input <file>     read the graph from a file instead of the sample\n");
    printf("  --stress <n>       traverse a path graph with n vertices to show that\n");
    printf("                     the iterative version does not use the call stack\n");
    printf("  -h, --help         show this help\n");
    printf("\n");
    printf("Input file format: \"V E\" followed by E lines \"u v\" (undirected\n");
    printf("edges) and an optional last line holding the start vertex.\n");
}

/*
 * Reads a graph from `path`.
 *
 *   V E
 *   u1 v1
 *   ...
 *   uE vE
 *   [start]
 */
static Graph *readGraphFromFile(const char *path, int *start)
{
    FILE *file = fopen(path, "r");
    Graph *graph = NULL;
    int values[2 * 4096 + 4];
    int count = 0;
    int numVertices;
    int numEdges;
    int i;

    if (file == NULL) {
        fprintf(stderr, "Cannot open graph file '%s'.\n", path);
        return NULL;
    }
    while (count < (int)(sizeof values / sizeof values[0]) &&
           fscanf(file, "%d", &values[count]) == 1) {
        count++;
    }
    fclose(file);

    if (count < 2) {
        fprintf(stderr, "Graph file '%s' is empty or malformed.\n", path);
        return NULL;
    }
    numVertices = values[0];
    numEdges = values[1];
    if (numVertices <= 0 || numEdges < 0) {
        fprintf(stderr, "Graph file '%s' declares an invalid graph.\n", path);
        return NULL;
    }
    if (count < 2 + 2 * numEdges) {
        fprintf(stderr, "Graph file '%s' declares %d edges but only %d endpoints were read.\n",
                path, numEdges, count - 2);
        return NULL;
    }

    graph = graphCreate(numVertices);
    if (graph == NULL) {
        fprintf(stderr, "Not enough memory for a graph with %d vertices.\n", numVertices);
        return NULL;
    }
    for (i = 0; i < numEdges; i++) {
        int src = values[2 + 2 * i];
        int dest = values[3 + 2 * i];
        if (src < 0 || src >= numVertices || dest < 0 || dest >= numVertices) {
            fprintf(stderr, "Edge %d-%d refers to a vertex outside 0..%d.\n",
                    src, dest, numVertices - 1);
            graphFree(graph);
            return NULL;
        }
        graphAddEdge(graph, src, dest);
    }
    if (count > 2 + 2 * numEdges) {
        *start = values[2 + 2 * numEdges];
    }
    return graph;
}

static Graph *sampleGraph(void)
{
    Graph *graph = graphCreate(6);

    if (graph == NULL) {
        return NULL;
    }
    graphAddEdge(graph, 0, 1);
    graphAddEdge(graph, 0, 2);
    graphAddEdge(graph, 1, 3);
    graphAddEdge(graph, 1, 4);
    graphAddEdge(graph, 2, 5);
    return graph;
}

/* ------------------------------------------------------------------ */
/*  Stress test: depth of the traversal vs. depth of the call stack     */
/* ------------------------------------------------------------------ */

static double secondsSince(const struct timespec *start, const struct timespec *end)
{
    return (double)(end->tv_sec - start->tv_sec) + (double)(end->tv_nsec - start->tv_nsec) / 1e9;
}

static int runStress(long vertices)
{
    Graph *graph;
    int *order;
    int count = 0;
    struct timespec start;
    struct timespec end;
    double elapsed;

    if (vertices < 2 || vertices > 100000000L) {
        fprintf(stderr, "The stress test needs a vertex count between 2 and 100000000.\n");
        return 1;
    }
    graph = graphCreate((int)vertices);
    if (graph == NULL) {
        fprintf(stderr, "Not enough memory for a path graph with %ld vertices.\n", vertices);
        return 1;
    }
    /* A path graph 0-1-2-...-(n-1) is the worst case for a recursive
       traversal: the recursion depth equals the number of vertices. */
    for (long v = 0; v + 1 < vertices; v++) {
        graphAddEdge(graph, (int)v, (int)(v + 1));
    }
    order = malloc((size_t)vertices * sizeof *order);
    if (order == NULL) {
        fprintf(stderr, "Not enough memory for the traversal order.\n");
        graphFree(graph);
        return 1;
    }

    printf("Stress test: path graph with %ld vertices and %ld edges.\n", vertices, vertices - 1);

    clock_gettime(CLOCK_MONOTONIC, &start);
    if (!dfsIterative(graph, 0, order, &count)) {
        fprintf(stderr, "The iterative traversal could not be executed.\n");
        free(order);
        graphFree(graph);
        return 1;
    }
    clock_gettime(CLOCK_MONOTONIC, &end);
    elapsed = secondsSince(&start, &end);
    printf("Iterative DFS (heap stack): visited %d vertices in %.3f s, first %d last %d.\n",
           count, elapsed, order[0], order[count - 1]);

#ifdef __unix__
    /* The recursive version is executed in a child process: when the call
       stack overflows the child dies with SIGSEGV while the parent stays
       alive and can report the result. */
    {
        pid_t child = fork();
        if (child == 0) {
            bool *visited = calloc((size_t)graph->numVertices, sizeof *visited);
            int *childOrder = malloc((size_t)graph->numVertices * sizeof *childOrder);
            int childCount = 0;

            if (visited != NULL && childOrder != NULL) {
                dfsRecursiveVisit(graph, 0, visited, childOrder, &childCount);
            }
            free(visited);
            free(childOrder);
            _exit(0);
        } else if (child > 0) {
            int status = 0;
            waitpid(child, &status, 0);
            if (WIFSIGNALED(status)) {
                printf("Recursive DFS (call stack): crashed with signal %d (call stack overflow).\n",
                       WTERMSIG(status));
            } else {
                printf("Recursive DFS (call stack): completed, exit status %d.\n", WEXITSTATUS(status));
            }
        }
    }
#else
    printf("Recursive DFS (call stack): not executed on this platform.\n");
#endif

    free(order);
    graphFree(graph);
    return 0;
}

/* ------------------------------------------------------------------ */
/*  main                                                               */
/* ------------------------------------------------------------------ */

int main(int argc, char **argv)
{
    Options options;
    Graph *graph;
    int *order;
    int *reference;
    int count = 0;
    int referenceCount = 0;
    char label[128];
    int i;

    options.start = 0;
    options.startGiven = false;
    options.variant = VARIANT_CANONICAL;
    options.compare = false;
    options.forest = false;
    options.showGraph = false;
    options.inputPath = NULL;
    options.stressVertices = 0;

    for (i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-h") == 0 || strcmp(argv[i], "--help") == 0) {
            printUsage(argv[0]);
            return 0;
        } else if (strcmp(argv[i], "--start") == 0 && i + 1 < argc) {
            options.start = atoi(argv[++i]);
            options.startGiven = true;
        } else if (strcmp(argv[i], "--variant") == 0 && i + 1 < argc) {
            const char *name = argv[++i];
            if (strcmp(name, "canonical") == 0) {
                options.variant = VARIANT_CANONICAL;
            } else if (strcmp(name, "notebook") == 0) {
                options.variant = VARIANT_NOTEBOOK;
            } else if (strcmp(name, "recursive") == 0) {
                options.variant = VARIANT_RECURSIVE;
            } else {
                fprintf(stderr, "Unknown variant '%s'. Use canonical, notebook or recursive.\n", name);
                return 2;
            }
        } else if (strcmp(argv[i], "--compare") == 0) {
            options.compare = true;
        } else if (strcmp(argv[i], "--forest") == 0) {
            options.forest = true;
        } else if (strcmp(argv[i], "--show-graph") == 0) {
            options.showGraph = true;
        } else if (strcmp(argv[i], "--input") == 0 && i + 1 < argc) {
            options.inputPath = argv[++i];
        } else if (strcmp(argv[i], "--stress") == 0 && i + 1 < argc) {
            options.stressVertices = atol(argv[++i]);
        } else {
            fprintf(stderr, "Unknown option '%s'. Use --help for the usage.\n", argv[i]);
            return 2;
        }
    }

    if (options.stressVertices > 0) {
        return runStress(options.stressVertices);
    }

    if (options.inputPath != NULL) {
        int fileStart = options.start;
        graph = readGraphFromFile(options.inputPath, &fileStart);
        if (graph != NULL && !options.startGiven) {
            options.start = fileStart;
        }
    } else {
        graph = sampleGraph();
    }
    if (graph == NULL) {
        return 1;
    }
    if (options.start < 0 || options.start >= graph->numVertices) {
        fprintf(stderr, "Start vertex %d is outside the graph (0..%d).\n",
                options.start, graph->numVertices - 1);
        graphFree(graph);
        return 1;
    }

    order = malloc((size_t)graph->numVertices * sizeof *order);
    reference = malloc((size_t)graph->numVertices * sizeof *reference);
    if (order == NULL || reference == NULL) {
        fprintf(stderr, "Not enough memory for the traversal order.\n");
        free(order);
        free(reference);
        graphFree(graph);
        return 1;
    }

    if (options.showGraph) {
        graphPrint(graph);
    }

    if (options.forest) {
        if (!dfsForest(graph, order, &count)) {
            fprintf(stderr, "The traversal could not be executed.\n");
            free(order);
            free(reference);
            graphFree(graph);
            return 1;
        }
        printOrder("DFS forest visiting every connected component", order, count);
    } else if (options.variant == VARIANT_NOTEBOOK) {
        if (!dfsNotebookVariant(graph, options.start, order, &count)) {
            fprintf(stderr, "The traversal could not be executed.\n");
            free(order);
            free(reference);
            graphFree(graph);
            return 1;
        }
        snprintf(label, sizeof label,
                 "Iterative DFS traversal (notebook pseudo code) starting from vertex %d", options.start);
        printOrder(label, order, count);
    } else if (options.variant == VARIANT_RECURSIVE) {
        if (!dfsRecursive(graph, options.start, order, &count)) {
            fprintf(stderr, "The traversal could not be executed.\n");
            free(order);
            free(reference);
            graphFree(graph);
            return 1;
        }
        snprintf(label, sizeof label, "Recursive DFS traversal starting from vertex %d", options.start);
        printOrder(label, order, count);
    } else {
        if (!dfsIterative(graph, options.start, order, &count)) {
            fprintf(stderr, "The traversal could not be executed.\n");
            free(order);
            free(reference);
            graphFree(graph);
            return 1;
        }
        snprintf(label, sizeof label, "Iterative DFS traversal starting from vertex %d", options.start);
        printOrder(label, order, count);
    }

    if (options.compare) {
        bool same;
        int j;

        if (options.forest) {
            if (!dfsRecursiveForest(graph, reference, &referenceCount)) {
                fprintf(stderr, "The recursive reference could not be executed.\n");
                free(order);
                free(reference);
                graphFree(graph);
                return 1;
            }
        } else if (!dfsRecursive(graph, options.start, reference, &referenceCount)) {
            fprintf(stderr, "The recursive reference could not be executed.\n");
            free(order);
            free(reference);
            graphFree(graph);
            return 1;
        }
        if (options.forest) {
            printOrder("Recursive DFS forest visiting every connected component", reference, referenceCount);
        } else {
            snprintf(label, sizeof label,
                     "Recursive DFS traversal starting from vertex %d", options.start);
            printOrder(label, reference, referenceCount);
        }

        same = (count == referenceCount);
        for (j = 0; same && j < count; j++) {
            same = (order[j] == reference[j]);
        }
        printf("Iterative and recursive traversals agree: %s\n", same ? "yes" : "no");
    }

    free(order);
    free(reference);
    graphFree(graph);
    return 0;
}
