GraalVM comes with **Graal VisualVM**, an enhanced version of the popular [VisualVM](https://visualvm.github.io) tool which includes special heap analysis features for the supported guest languages. These languages and features are currently available:

 - __Java:__ Heap Summary, Objects View, Threads View, OQL Console
 - __JavaScript:__ Heap Summary, Objects View, Thread View
 - __Python:__ Heap Summary, Objects View
 - __Ruby:__ Heap Summary, Objects View, Threads View
 - __R:__ Heap Summary, Objects View

### Starting Graal VisualVM
To start Graal VisualVM execute `jvisualvm`. Immediately after startup the tool shows all locally running Java processes in the Applications area, including the VisualVM process itself.

__NOTE:__ the native image processes are not displayed and cannot be analyzed using Graal VisualVM. If you can't see your process in the Applications area, you should use the `--jvm` switch when starting the process to make sure it does not run in SVM.

### Getting Heap Dump
Let's say you are trying to analyze a Ruby application. To get a heap dump, first start your application and let it run for a few seconds to warm up.

Then right-click its process in VisualVM and invoke the Heap Dump action. A new heap viewer for the Ruby process opens.

### Analyzing Objects
Initially the Summary view for the Java heap is displayed. To analyze the Ruby heap, click the leftmost (Summary) dropdown in the heap viewer toolbar, choose the Ruby Heap scope and select the Objects view. Now the heap viewer displays all Ruby heap objects, aggregated by their type.

Expand the Proc node in the results view to see a list of objects of this type. Each object displays its logical value as provided by the underlying implementation. Expand the objects to access their variables and references, where available.

![](/docs/img/HeapViewer_objects.png "Graal VisualVM Heap Viewer - analyzing objects")

Now enable the Preview, Variables and References details by clicking the buttons in the toolbar and select the individual ProcType objects. Where available, the Preview view shows the corresponding source fragment, the Variables view shows variables of the object and References view shows objects referring to the selected object.

Last, use the Presets dropdown in the heap viewer toolbar to switch the view from All Objects to Dominators or GC Roots. To display the heap dominators, retained sizes must be computed first, which can take a few minutes for the server.rb example. Select the Objects aggregation in the toolbar to view the individual dominators or GC roots.

![](/docs/img/HeapViewer_objects_dominators.png "Graal VisualVM Heap Viewer - analyzing objects")

### Analyzing Threads
Click the leftmost dropdown in the heap viewer toolbar and select the Threads view for the Ruby heap. The heap viewer now displays the Ruby thread stack trace, including local objects. The stack trace can alternatively be displayed textually by clicking the HTML toolbar button.

![](/docs/img/HeapViewer_thread.png "Graal VisualVM Heap Viewer - analyzing thread")
