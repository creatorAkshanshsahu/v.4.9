# DAA Lab 6 - iterative DFS
#
#   make          build the program
#   make run      build and run it on the sample graph
#   make compare  build and compare the iterative and the recursive traversal
#   make forest   build and traverse every connected component
#   make stress   build and run the deep graph stress test
#   make test     build and run the test suite
#   make clean    remove the binary

CC     ?= gcc
CFLAGS ?= -std=c11 -Wall -Wextra -pedantic -O2
BIN     = dfs
SRC     = dfs.c

.PHONY: all run compare forest stress test clean

all: $(BIN)

$(BIN): $(SRC)
	$(CC) $(CFLAGS) -o $(BIN) $(SRC)

run: $(BIN)
	./$(BIN)

compare: $(BIN)
	./$(BIN) --compare

forest: $(BIN)
	./$(BIN) --forest --compare

stress: $(BIN)
	./$(BIN) --stress 200000

test: $(BIN)
	python3 -B tests/test_iterative_dfs.py

clean:
	rm -f $(BIN)
