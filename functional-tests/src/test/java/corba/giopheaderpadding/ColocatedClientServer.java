/*
 * Copyright (c) 1997, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0, which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

//
// Created       : 2003 Apr 17 (Thu) 17:05:00 by Harold Carr.
// Last Modified : 2004 Jan 31 (Sat) 10:57:47 by Harold Carr.
//

package corba.giopheaderpadding;

import java.util.Properties;
import javax.naming.InitialContext;
import org.omg.CORBA.ORB;
import corba.hcks.C;
import corba.hcks.U;

public class ColocatedClientServer 
{
    public static final String baseMsg = ColocatedClientServer.class.getName();
    public static final String main = baseMsg + ".main";

    // REVISIT: FRAMEWORK DEVELOPMENT
    // REMOVE THIS LATER.
    // Necessary so calls not going through locals do not hang
    // until I implement the reader thread/work split.
    public static int fragmentSize = -1;
    //public static int fragmentSize = C.DEFAULT_FRAGMENT_SIZE;

    public static ORB orb;
    public static InitialContext initialContext;
    public static boolean isColocated = false;
    public static java.lang.Object signal = new java.lang.Object();

    public static void main (String[] av)
    {
        isColocated = true; // Used by Client and Server.

        try {
            // Share an ORB between a client and server.
            // So ClientDelegate.isLocal currently succeeds.

            Properties props = new Properties();
            props.setProperty("com.sun.corba.ee.ORBAllowLocalOptimization",
                              "true");
            orb = ORB.init(av, props);
            U.sop(main + " : creating ORB.");
            Server.orb = (com.sun.corba.ee.spi.orb.ORB) orb;
            Client.orb = orb;
            
            // Share a naming context between client and server
            // so Util.isLocal is true.

            // Use the same ORB which has interceptor properties set.
            U.sop(main + " : creating InitialContext.");
            initialContext = C.createInitialContext(orb);
            Server.initialContext = initialContext;
            Client.initialContext = initialContext;
            
            ServerThread ServerThread = new ServerThread(av);
            ServerThread.start();
            synchronized (signal) {
                try {
                    signal.wait();
                } catch (InterruptedException e) {
                    ;
                }
            }
            Client.main(av);
        } catch (Exception e) {
            U.sopUnexpectedException(main, e);
            System.exit(1);
        }
    }
}

class ServerThread extends Thread
{
    String[] args;
    ServerThread (String[] args)
    {
        this.args = args;
    }
    public void run ()
    {
        Server.main(args);
    }
}

// End of file.
