- [Parallel Calculator implementation using Akka Streams](#org27aa96a)
- [Performance Discussion](#org822fdd0)
  - [Test log with clear results](#org8213754)
  - [Comment on results](#org82d0100)
- [Expression representation to faciliate parallel computation - biggest question (initial approach)](#orgd147116)
  - [<span class="underline">My algorithm:</span>](#orgc1d964f)
  - [how does akka streams fit with my problem? how to represent it using Streams?](#orgb2d1466)
- [Stream streams streams](#orgfcc5f7a)
  - [simple idea](#orgc11609b)
  - [execution model](#org06783b2)
- [String to Expression parsing <code>[3/3]</code>](#org448527f)
- [Http aspects <code>[7/7]</code>](#org93b3634)
- [better tests <code>[4/4]</code>](#org2489320)
- [Possible development areas:](#orgf69f11f)
- [readme fixups <code>[4/4]</code>](#org59cf087)
- [time report on task](#org726994e)


<a id="org27aa96a"></a>

# Parallel Calculator implementation using Akka Streams

As requested in [task description](scala-challenge.pdf), calculator runs on Akka HTTP server. String input is parsed using Scala's parsers combinators and then evaluated by transforming Expression AST into Akka Streams graph in an attempt to use [pipelining and parallelism](https://doc.akka.io/docs/akka/current/scala/stream/stream-parallelism.html).


<a id="org822fdd0"></a>

# Performance Discussion


<a id="org8213754"></a>

## Test log with clear results
```
> runMain testapp.PerformanceTestRunner
[info] Compiling 2 Scala sources to /home/tlegutko/test-app/target/scala-2.12/classes&#x2026;
[info] Compiling 4 Scala sources to /home/tlegutko/test-app/target/scala-2.12/classes&#x2026;
[info] Running testapp.PerformanceTestRunner
[info] processing 8191 operations
[info] single thread, res -3.8064334146196444E18, time 0.004s
[info] streams, single thread, res -3.8064334146196444E18, time 0.51s
[info] parallel streams, res -3.8064334146196444E18, time 0.778s
[info] processing 16383 operations
[info] single thread, res 1.2798923557445245E15, time 0.001s
[info] streams, single thread, res 1.2798923557445245E15, time 0.188s
[info] parallel streams, res 1.2798923557445245E15, time 0.913s
[info] processing 32767 operations
[info] single thread, res 2.0120662019093847E-8, time 0.002s
[info] streams, single thread, res 2.0120662019093847E-8, time 0.443s
[info] parallel streams, res 2.0120662019093847E-8, time 1.424s
[info] processing 65535 operations
[info] single thread, res 2.352968664954164E33, time 0.002s
[info] streams, single thread, res 2.352968664954164E33, time 0.913s
[info] parallel streams, res 2.352968664954164E33, time 4.542s
[info] processing 131071 operations
[info] single thread, res 7.195469462287226E43, time 0.006s
[info] streams, single thread, res 7.195469462287226E43, time 1.284s
[info] parallel streams, res 7.195469462287226E43, time 8.018s
[info] processing 262143 operations
[info] single thread, res 1.2599112194339391E38, time 0.009s
[info] streams, single thread, res 1.2599112194339391E38, time 4.336s
[info] parallel streams, res 1.2599112194339391E38, time 14.409s [success] Total time: 48 s, completed Oct 9, 2017 3:31:05 AM
```

<a id="org82d0100"></a>

## Comment on results

Operation performed in various thread (arithmetic operation on two numbers) clearly takes less time than the time needed for data passing between asynchronous boundary between threads, as explained [here](https://stackoverflow.com/a/33420936/3551299). As a future work it'd be great to test some other algorithm (i.e. the one described below), but maybe using Futures directly.


<a id="orgd147116"></a>

# Expression representation to faciliate parallel computation - biggest question (initial approach)

1.  There are evidently sequential, non-parallelizable parts of computation. (3+2)\*4 needs 2 computational stages
2.  There are many strategies for parallelism utilization for this problem
3.  Changing division and subtraction to multiplication allows to reduce number of stages and associativeness
4.  But that could be problematic to handle / or - signs before parentheses


<a id="orgc1d964f"></a>

## <span class="underline">My algorithm:</span>

1.  Consider all expressions with the highest-level nesting of parentheses in parallel
2.  Change division to multiplication and perform them all in parallel
3.  Change subtraction to addition and perform them all in parallel
4.  Remove meaningless parentheses and if expression is not a number, go back to step 1.


<a id="orgb2d1466"></a>

## how does akka streams fit with my problem? how to represent it using Streams?

I've figured out graph facilitating async stages Source ~> Fan-Out ~> (async, multiple) ~> DivMulFlow ~> MulEWPF ~> SubAddFlow ~> AddEWPF ~> FanInLooper ~> Sink FanInLooper ~> Source EWPF - Eval Worker Pool Fixed - async worker pool for multiplication and adding, see [here](https://doc.akka.io/docs/akka/2.5.3/scala/stream/stream-cookbook.html#cookbook-balance) 
Finally I've decided for simpler approach with representing expression tree as stream graph.


<a id="orgfcc5f7a"></a>

# Stream streams streams


<a id="orgc11609b"></a>

## simple idea

map each of Expression tree's leaves to a stream source and have a stream sink at the tree's root. conceptually it's way easier than previous algorithm, should work, but what about parallelism in execution model?


<a id="org06783b2"></a>

## execution model

"To run a stage asynchronously it has to be marked explicitly as such using the .async method. <span class="underline">Being run asynchronously means that a stage, after handing out an element to its downstream consumer is able to immediately process the next message.</span>" - That line from docs.. that's not what I want, in my case each flow processes two elements only via Zip[A,B]. "The default behavior of Akka Streams is to put all computations of a graph (where possible) in the same single-threaded “island” (I carefully avoid to say thread here, as the reality is more complicated, but bear with me) **unless instructed otherwise.**" [source](https://akka.io/blog/2016/07/06/threading-and-concurrency-in-akka-streams-explained) But that's perfectly what I hoped it would be! Stages run on different threads from a thread pool, that's perfect. Default execution config looks good enough ([here](https://doc.akka.io/docs/akka/snapshot/scala/dispatchers.html) and [here](https://stackoverflow.com/questions/16175725/what-are-the-default-akka-dispatcher-configuration-values)).


<a id="org448527f"></a>

# String to Expression parsing <code>[3/3]</code>

Combinator parsers to the rescue, nice!

-   [X] error cases <code>[3/3]</code> I should be able to find those out during parsing
    -   [X] division by 0 - this one's a challenge
    -   [X] improperly formatted, eg. "1/", "(1+3" - error thanks to parsers combinators
    -   [X] spaces besides operators - works
-   [X] associativity of operators
-   [X] parentheses handling


<a id="org93b3634"></a>

# Http aspects <code>[8/8]</code>

-   [X] content type application/json
-   [X] format what's returned to be in json {expression: "blabla"}
-   [X] [marshalling / demarshalling](https://doc.akka.io/docs/akka-http/current/scala/http/common/json-support.html)
-   [X] test it using curl, as in pdf
-   [X] figure out error handling (and code for it) (code 422 (UnprocessableEntity))
-   [X] change get to post and figure out parameters
-   [X] figure out nicely formated error response (string => json with reason). it's doable, but too much work and it's consistent with other 404 / 405 responses being just string
-   [X] new DecimalFormat("#.##").format(1.199) to get rid of trailing 0 in double


<a id="org2489320"></a>

# better tests <code>[4/4]</code>

-   [X] generate huge test case to somehow test how parallellism is performing
-   [X] some http tests (include various errors!)
-   [X] string to expr (parsers combinators)
-   [X] some basic stream evaluation tests


<a id="orgf69f11f"></a>

# Possible development areas:

-   what if we were to add exponents or roots or other operations? just next level in parser grammar grammar as well as next Expression and operation in Streams parsing. definitely doable.
-   **how to get parallelism that would perform better?**


<a id="org59cf087"></a>

# readme fixups <code>[4/4]</code>

-   [X] rearrange sections
-   [X] intro
-   [X] performance comment
-   [X] timetable report


<a id="org726994e"></a>

# time report on task

| Headline                                                   | Time        |         |      |
|---------------------------------------------------------- |----------- |------- |---- |
| **Total time**                                             | **1d 7:46** |         |      |
| &ensp;&ensp;test-app                                       |             | 1d 7:46 |      |
| &ensp;&ensp;&ensp;&ensp;designing expression model + graph |             |         | 3:58 |
| &ensp;&ensp;&ensp;&ensp;akka streams                       |             |         | 3:30 |
| &ensp;&ensp;&ensp;&ensp;akka http                          |             |         | 1:46 |
| &ensp;&ensp;&ensp;&ensp;parsers                            |             |         | 3:12 |
| &ensp;&ensp;&ensp;&ensp;parallelism impl                   |             |         | 4:31 |
| &ensp;&ensp;&ensp;&ensp;cleanup and error handling         |             |         | 2:51 |
| &ensp;&ensp;&ensp;&ensp;tests                              |             |         | 4:53 |
| &ensp;&ensp;&ensp;&ensp;finishing docs                     |             |         | 0:21 |
