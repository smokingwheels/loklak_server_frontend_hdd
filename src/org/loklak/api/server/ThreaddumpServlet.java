/**
 *  ThreaddumpServlet
 *  Copyright 03.07.2015 by Michael Peter Christen, @0rb1t3r
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *  
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *  
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package org.loklak.api.server;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.Thread.State;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.loklak.Caretaker;
import org.loklak.data.DAO;
import org.loklak.http.RemoteAccess;
import org.loklak.tools.CharacterCoding;
import org.loklak.tools.UTF8;

public class ThreaddumpServlet extends HttpServlet {

    private static final long serialVersionUID = -7095346222464124198L;

    private static final String multiDumpFilter = ".*((java.net.DatagramSocket.receive)|(java.lang.Thread.getAllStackTraces)|(java.net.SocketInputStream.read)|(java.net.ServerSocket.accept)|(java.net.Socket.connect)).*";
    private static final Pattern multiDumpFilterPattern = Pattern.compile(multiDumpFilter);
    private static ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private static OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    
    @Override
    protected void doPost(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException {
        doGet(request, response);
    }

    @Override
    protected void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException {
        RemoteAccess.Post post = RemoteAccess.evaluate(request);

        String servlet = post.isLocalhostAccess() ? post.get("servlet", "") : "";
        if (servlet.length() > 0) {
            try {
                final Class<?> servletClass = ClassLoader.getSystemClassLoader().loadClass("org.loklak.api.server." + servlet);
                final Method getMethod = servletClass.getDeclaredMethod("doGet", HttpServletRequest.class, HttpServletResponse.class);
                final Thread servletThread = new Thread(new Runnable() {
                    public void run() {
                        try {
                            getMethod.invoke(servletClass.newInstance(), request, new DummyResponse());
                        } catch (IllegalArgumentException | InvocationTargetException | InstantiationException | IllegalAccessException e) {
                            e.printStackTrace();
                        }
                    }
                });
                servletThread.start();
            } catch (ClassNotFoundException | NoSuchMethodException | SecurityException e) {
                e.printStackTrace();
            }
            long sleep = post.get("sleep", 0L);
            if (sleep > 0) try {Thread.sleep(sleep);} catch (InterruptedException e) {}
        }

        int multi = post.isLocalhostAccess() ? post.get("multi", 0) : 0;
        final StringBuilder buffer = new StringBuilder(1000);

        // Thread dump
        final Date dt = new Date();
        Runtime runtime = Runtime.getRuntime();

        int keylen = 30;
        bufferappend(buffer, "************* Start Thread Dump " + dt + " *******************");
        bufferappend(buffer, "");
        bufferappend(buffer, keylen, "Assigned   Memory", runtime.maxMemory());
        bufferappend(buffer, keylen, "Used       Memory", runtime.totalMemory() - runtime.freeMemory());
        bufferappend(buffer, keylen, "Available  Memory", runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory());
        bufferappend(buffer, keylen, "Cores", runtime.availableProcessors());
        bufferappend(buffer, keylen, "Active Thread Count", Thread.activeCount());
        bufferappend(buffer, keylen, "Total Started Thread Count", threadBean.getTotalStartedThreadCount());
        bufferappend(buffer, keylen, "Peak Thread Count", threadBean.getPeakThreadCount());
        bufferappend(buffer, keylen, "System Load Average", osBean.getSystemLoadAverage());
        long runtimeseconds = (System.currentTimeMillis() - Caretaker.startupTime) / 1000;
        int runtimeminutes = (int) (runtimeseconds / 60); runtimeseconds = runtimeseconds % 60;
        int runtimehours = runtimeminutes / 60; runtimeminutes = runtimeminutes % 60;
        bufferappend(buffer, keylen, "Runtime", runtimehours + "h " + runtimeminutes + "m " + runtimeseconds + "s");
        long timetorestartseconds = (Caretaker.upgradeTime - System.currentTimeMillis()) / 1000;
        int timetorestartminutes = (int) (timetorestartseconds / 60); timetorestartseconds = timetorestartseconds % 60;
        int timetorestarthours = timetorestartminutes / 60; timetorestartminutes = timetorestartminutes % 60;
        bufferappend(buffer, keylen, "Time To Restart", timetorestarthours + "h " + timetorestartminutes + "m " + timetorestartseconds + "s");
        // print system beans
        for (Method method : osBean.getClass().getDeclaredMethods()) try {
            method.setAccessible(true);
            if (method.getName().startsWith("get") && Modifier.isPublic(method.getModifiers())) {
                bufferappend(buffer, keylen, method.getName(), method.invoke(osBean));
            }
        } catch (Throwable e) {}
        
        bufferappend(buffer, "");
        bufferappend(buffer, "");

        if (multi > 0) {
            final ArrayList<Map<Thread,StackTraceElement[]>> traces = new ArrayList<Map<Thread,StackTraceElement[]>>();
            for (int i = 0; i < multi; i++) {
                try {
                    traces.add(ThreadDump.getAllStackTraces());
                } catch (final OutOfMemoryError e) {
                    break;
                }
            }
            appendStackTraceStats(buffer, traces);
        } else {
            // generate a single thread dump
            final Map<Thread,StackTraceElement[]> stackTraces = ThreadDump.getAllStackTraces();
            new ThreadDump(stackTraces, Thread.State.BLOCKED).appendStackTraces(buffer, Thread.State.BLOCKED);
            new ThreadDump(stackTraces, Thread.State.RUNNABLE).appendStackTraces(buffer, Thread.State.RUNNABLE);
            new ThreadDump(stackTraces, Thread.State.TIMED_WAITING).appendStackTraces(buffer, Thread.State.TIMED_WAITING);
            new ThreadDump(stackTraces, Thread.State.WAITING).appendStackTraces(buffer, Thread.State.WAITING);
            new ThreadDump(stackTraces, Thread.State.NEW).appendStackTraces(buffer, Thread.State.NEW);
            new ThreadDump(stackTraces, Thread.State.TERMINATED).appendStackTraces(buffer, Thread.State.TERMINATED);
        }

        ThreadMXBean threadbean = ManagementFactory.getThreadMXBean();
        bufferappend(buffer, "");
        bufferappend(buffer, "THREAD LIST FROM ThreadMXBean, " + threadbean.getThreadCount() + " threads:");
        bufferappend(buffer, "");
        ThreadInfo[] threadinfo = threadbean.dumpAllThreads(true, true);
        for (ThreadInfo ti: threadinfo) {
            bufferappend(buffer, ti.getThreadName());
        }

        bufferappend(buffer, "");
        bufferappend(buffer, "ELASTICSEARCH ClUSTER STATS");
        bufferappend(buffer, DAO.clusterStats());

        bufferappend(buffer, "");
        bufferappend(buffer, "ELASTICSEARCH PENDING ClUSTER TASKS");
        bufferappend(buffer, DAO.pendingClusterTasks());
        
        if (post.isLocalhostAccess()) {
            // this can reveal private data, so keep it on localhost access only
            bufferappend(buffer, "");
            bufferappend(buffer, "ELASTICSEARCH NODE SETTINGS");
            bufferappend(buffer, DAO.nodeSettings().toString());
        }

        FileHandler.setCaching(response, 10);
        post.setResponse(response, "text/plain");
        response.getOutputStream().write(UTF8.getBytes(buffer.toString()));
        post.finalize();
    }

    private static class StackTrace {
        private String text;
        private StackTrace(final String text) {
            this.text = text;
        }
        @Override
        public boolean equals(final Object a) {
            return (a != null && a instanceof StackTrace && this.text.equals(((StackTrace) a).text));
        }
        @Override
        public int hashCode() {
            return this.text.hashCode();
        }
        @Override
        public String toString() {
            return this.text;
        }
    }
    
    private static void appendStackTraceStats(
            final StringBuilder buffer,
            final List<Map<Thread, StackTraceElement[]>> stackTraces) {

        // collect single dumps
        final Map<String, Integer> dumps = new HashMap<String, Integer>();
        ThreadDump x;
        for (final Map<Thread, StackTraceElement[]> trace: stackTraces) {
            x = new ThreadDump(trace, Thread.State.RUNNABLE);
            for (final Map.Entry<StackTrace, List<String>> e: x.entrySet()) {
                if (multiDumpFilterPattern.matcher(e.getKey().text).matches()) continue;
                Integer c = dumps.get(e.getKey().text);
                if (c == null) dumps.put(e.getKey().text, Integer.valueOf(1));
                else {
                    c = Integer.valueOf(c.intValue() + 1);
                    dumps.put(e.getKey().text, c);
                }
            }
        }

        // write dumps
        while (!dumps.isEmpty()) {
            final Map.Entry<String, Integer> e = removeMax(dumps);
            bufferappend(buffer, "Occurrences: " + e.getValue());
            bufferappend(buffer, e.getKey());
            bufferappend(buffer, "");
        }
        bufferappend(buffer, "");
    }

    private static Map.Entry<String, Integer> removeMax(final Map<String, Integer> result) {
        Map.Entry<String, Integer> max = null;
        for (final Map.Entry<String, Integer> e: result.entrySet()) {
            if (max == null || e.getValue().intValue() > max.getValue().intValue()) {
                max = e;
            }
        }
        result.remove(max.getKey());
        return max;
    }
    
    private static void bufferappend(final StringBuilder buffer, int keylen, final String key, Object value) {
        if (value instanceof Double)
            bufferappend(buffer, keylen, key, ((Double) value).toString());
        else if (value instanceof Number)
            bufferappend(buffer, keylen, key, ((Number) value).longValue());
        else
            bufferappend(buffer, keylen, key, value.toString());
    }
    
    private static final DecimalFormat cardinalFormatter = new DecimalFormat("###,###,###,###,###");
    private static void bufferappend(final StringBuilder buffer, int keylen, final String key, long value) {
        bufferappend(buffer, keylen, key, cardinalFormatter.format(value));
    }
    
    private static void bufferappend(final StringBuilder buffer, int keylen, final String key, String value) {
        String a = key;
        while (a.length() < keylen) a += " ";
        a += "=";
        for (int i = value.length(); i < 20; i++) a += " ";
        a += value;
        bufferappend(buffer, a);
    }
    
    private static void bufferappend(final StringBuilder buffer, final String a) {
        buffer.append(a);
        buffer.append('\n');
    }
    
    private static class ThreadDump extends HashMap<StackTrace, List<String>> implements Map<StackTrace, List<String>> {

        private static final long serialVersionUID = -5587850671040354397L;

        private static Map<Thread, StackTraceElement[]> getAllStackTraces() {
            return Thread.getAllStackTraces();
        }

        private ThreadDump(
                final Map<Thread, StackTraceElement[]> stackTraces,
                final Thread.State stateIn) {
            super();

            Thread thread;
            // collect single dumps
            for (final Map.Entry<Thread, StackTraceElement[]> entry: stackTraces.entrySet()) {
                thread = entry.getKey();
                final StackTraceElement[] stackTraceElements = entry.getValue();
                StackTraceElement ste;
                String tracename = "";
                final State threadState = thread.getState();
                final ThreadInfo info = threadBean.getThreadInfo(thread.getId());
                if (threadState != null && info != null && (stateIn == null || stateIn.equals(threadState)) && stackTraceElements.length > 0) {
                    final StringBuilder sb = new StringBuilder(3000);
                    final String threadtitle = tracename + "Thread= " + thread.getName() + " " + (thread.isDaemon()?"daemon":"") + " id=" + thread.getId() + " " + threadState.toString() + (info.getLockOwnerId() >= 0 ? " lock owner =" + info.getLockOwnerId() : "");
                    boolean cutcore = true;
                    for (int i = 0; i < stackTraceElements.length; i++) {
                        ste = stackTraceElements[i];
                        String className = ste.getClassName();
                        String classString = ste.toString();
                        if (cutcore && (className.startsWith("java.") || className.startsWith("sun."))) {
                            sb.setLength(0);
                            bufferappend(sb, tracename + "at " + classString);
                        } else {
                            cutcore = false;
                            bufferappend(sb, tracename + "at " + classString);
                        }
                    }
                    final String threaddump = sb.toString();
                    List<String> threads = get(threaddump);
                    if (threads == null) threads = new ArrayList<String>();
                    threads.add(threadtitle);
                    put(new StackTrace(threaddump), threads);
                }
            }
        }

        private void appendStackTraces(
                final StringBuilder buffer,
                final Thread.State stateIn) {
            bufferappend(buffer, "THREADS WITH STATES: " + stateIn.toString());
            bufferappend(buffer, "");

            // write dumps
            for (final Map.Entry<StackTrace, List<String>> entry: entrySet()) {
                final List<String> threads = entry.getValue();
                for (final String t: threads) bufferappend(buffer, t);
                bufferappend(buffer, entry.getKey().text);
                bufferappend(buffer, "");
            }
            bufferappend(buffer, "");
        }

    }
    
    private static class DummyResponse implements HttpServletResponse {
        @Override public void flushBuffer() throws IOException {}
        @Override public int getBufferSize() {return 2048;}
        @Override public String getCharacterEncoding() {return "UTF-8";}
        @Override public String getContentType() {return "text/plain";}
        @Override public Locale getLocale() {return Locale.ENGLISH;}
        @Override public boolean isCommitted() {return true;}
        @Override public void reset() {}
        @Override public void resetBuffer() {}
        @Override public void setBufferSize(int arg0) {}
        @Override public void setCharacterEncoding(String arg0) {}
        @Override public void setContentLength(int arg0) {}
        @Override public void setContentLengthLong(long arg0) {}
        @Override public void setContentType(String arg0) {}
        @Override public void setLocale(Locale arg0) {}
        @Override public void addCookie(Cookie arg0) {}
        @Override public void addDateHeader(String arg0, long arg1) {}
        @Override public void addHeader(String arg0, String arg1) {}
        @Override public void addIntHeader(String arg0, int arg1) {}
        @Override public boolean containsHeader(String arg0) {return true;}
        @Override public String encodeRedirectURL(String arg0) {return arg0;}
        @Override public String encodeRedirectUrl(String arg0) {return arg0;}
        @Override public String encodeURL(String arg0) {return arg0;}
        @Override public String encodeUrl(String arg0) {return arg0;}
        @Override public String getHeader(String arg0) {return "";}
        @Override public Collection<String> getHeaderNames() {return new ArrayList<String>(0);}
        @Override public Collection<String> getHeaders(String arg0) {return new ArrayList<String>(0);}
        @Override public int getStatus() {return 200;}
        @Override public void sendError(int arg0) throws IOException {}
        @Override public void sendError(int arg0, String arg1) throws IOException {}
        @Override public void sendRedirect(String arg0) throws IOException {}
        @Override public void setDateHeader(String arg0, long arg1) {}
        @Override public void setHeader(String arg0, String arg1) {}
        @Override public void setIntHeader(String arg0, int arg1) {}
        @Override public void setStatus(int arg0) {}
        @Override public void setStatus(int arg0, String arg1) {}
        @Override public PrintWriter getWriter() throws IOException {return new PrintWriter(new OutputStreamWriter(getOutputStream(), "UTF-8"));}
        @Override public ServletOutputStream getOutputStream() throws IOException {return new ServletOutputStream(){
            public void write(int aByte) throws IOException {}
            public boolean isReady() { return true; }
            public void setWriteListener(WriteListener arg0) {}
        };}
    }
}
