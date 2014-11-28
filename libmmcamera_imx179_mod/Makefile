CXX := clang++
CPPFLAGS := -Iendian/include
CXXFLAGS := -std=c++1z -Wall -Wextra -pedantic -O2
LDFLAGS := -lboost_program_options

PROG := imx179_patch
SRCS := main.cpp

.PHONY: all clean

all: $(PROG)

$(PROG): $(SRCS)
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) $(LDFLAGS) $^ -o $@

clean:
	-rm $(PROG)
