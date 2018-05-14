/*
 * Copyright (c) 2017-2018 Aion foundation.
 *
 * This file is part of the aion network project.
 *
 * The aion network project is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or any later version.
 *
 * The aion network project is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 * PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with the aion network
 * project source files. If not, see <https://www.gnu.org/licenses/>.
 *
 * The aion network project leverages useful source code from other open source projects. We
 * greatly appreciate the effort that was invested in these projects and we thank the individual
 * contributors for their work. For provenance information and contributors. Please see
 * <https://github.com/aionnetwork/aion/wiki/Contributors>.
 *
 * Contributors to the aion source files in decreasing order of code volume:
 * Aion foundation.
 * <ether.camp> team through the ethereumJ library.
 * Ether.Camp Inc. (US) team through Ethereum Harmony.
 * John Tromp through the Equihash solver.
 * Samuel Neves through the BLAKE2 implementation.
 * Zcash project team. Bitcoinj team.
 */
package org.aion.api.server.zmq;

import static org.aion.api.server.pb.ApiAion0.JAVAAPI_VAR;
import static org.zeromq.ZMQ.DEALER;
import static org.zeromq.ZMQ.ROUTER;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aion.api.server.ApiUtil;
import org.aion.api.server.pb.IHdlr;
import org.aion.api.server.pb.Message;
import org.aion.api.server.types.EvtContract;
import org.aion.api.server.types.Fltr;
import org.aion.api.server.types.TxPendingStatus;
import org.aion.base.util.ByteUtil;
import org.aion.base.util.Hex;
import org.aion.log.AionLoggerFactory;
import org.aion.log.LogEnum;
import org.aion.mcf.config.CfgApiZmq;
import org.slf4j.Logger;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Context;
import org.zeromq.ZMQ.Socket;

public class ProtocolProcessor implements Runnable {

    protected static final Logger LOG = AionLoggerFactory.getLogger(LogEnum.API.name());
    private final IHdlr handler;
    private static final String AION_ZMQ_WK_TH = "inproc://aionZmqWkTh";
    private static final String AION_ZMQ_CB_TH = "inproc://aionZmqCbTh";
    private static final String AION_ZMQ_EV_TH = "inproc://aionZmqEvTh";
    private static final String AION_ZMQ_HB_TH = "inproc://aionZmqHbTh";

    private CfgApiZmq cfgApi;
    private AtomicBoolean shutDown = new AtomicBoolean();

    private static final long zmqHWM = 100_000;
    private static final int SOCKETID_LEN = 5;

    public ProtocolProcessor(IHdlr _handler, final CfgApiZmq cfg) {
        this.handler = _handler;
        this.cfgApi = cfg;
    }

    public void shutdown() throws InterruptedException {
        handler.shutDown();
        Proxy.shutdown();
    }

    @Override
    public void run() {
        LOG.info("Starting Aion Api Server <port={}>", cfgApi.getPort());
        String bindAddr = "tcp://" + cfgApi.getIp() + ":" + cfgApi.getPort();
        int msgTh = 5;

        try {
            // create context.
            Context ctx = ZMQ.context(1);

            // create router sock.
            Socket feSock = ctx.socket(ROUTER);
            feSock.setSndHWM(zmqHWM);
            feSock.bind(bindAddr);

            Socket wkSocks = ctx.socket(DEALER);
            wkSocks.bind(AION_ZMQ_WK_TH);

            Socket cbSock = ctx.socket(DEALER);
            cbSock.bind(AION_ZMQ_CB_TH);

            Socket evSock = ctx.socket(DEALER);
            evSock.bind(AION_ZMQ_EV_TH);

            Socket hbSock = ctx.socket(DEALER);
            hbSock.bind(AION_ZMQ_HB_TH);

            ExecutorService es = Executors.newFixedThreadPool(msgTh);
            es.execute(() -> callbackRun(ctx));
            es.execute(this::txWaitRun);
            es.execute(() -> eventRun(ctx));
            es.execute(() -> workerRun(ctx));
            es.execute(() -> hbRun(ctx));

            Proxy.proxy(feSock, wkSocks, cbSock, evSock, hbSock);

            if (LOG.isInfoEnabled()) {
                LOG.info("ProtocolProcessor.run thread finish.");
            }

            if (LOG.isInfoEnabled()) {
                LOG.info("Shutting down Sockets...");
            }
            shutDown.set(true);
            // Shutdown HdlrZmq
            ((HdlrZmq) handler).shutdown();
            // Shutdown ZmqSocket
            feSock.close();
            wkSocks.close();
            cbSock.close();
            evSock.close();
            hbSock.close();
            // Shutdown ExecutorService
            es.shutdown();

            ctx.close();
            if (LOG.isInfoEnabled()) {
                LOG.info("Shutdown Sockets... Done!");
            }

        } catch (Exception e) {
            if (LOG.isErrorEnabled()) {
                LOG.error("ProtocolProcessor.run exception: " + e.getMessage());
            }
        }
    }

    private void eventRun(Context ctx) {
        Socket sock = ctx.socket(ZMQ.DEALER);
        sock.connect(AION_ZMQ_EV_TH);
        while (!shutDown.get()) {
            Map<Long, Fltr> filters = ((HdlrZmq) this.handler).getFilter();
            for (Long i : filters.keySet()) {
                Fltr f = filters.get(i);

                if (LOG.isTraceEnabled()) {
                    LOG.trace("ProtocolProcessor.eventRun fltr type:{}", f.getType());
                }

                if (f.getType() == Fltr.Type.EVENT) {
                    Object[] objs = f.poll();
                    List<Message.t_EventCt> al = new ArrayList<>();
                    for (int idx = 0; idx < objs.length; idx++) {
                        al.add(((EvtContract) objs[idx]).getMsgEventCt());
                        if (LOG.isTraceEnabled()) {
                            LOG.trace(
                                    "ProtocolProcessor.eventRun fltr event[{}]",
                                    ((EvtContract) objs[idx]).toJSON());
                        }
                    }

                    if (!al.isEmpty()) {
                        Message.rsp_EventCtCallback ecb =
                                Message.rsp_EventCtCallback.newBuilder().addAllEc(al).build();
                        byte[] rsp = ((HdlrZmq) this.handler).toRspEvtMsg(ecb.toByteArray());

                        try {
                            byte[] socketId =
                                    ByteBuffer.allocate(5)
                                            .put(ByteUtil.longToBytes(i), 3, 5)
                                            .array();
                            sock.send(socketId, ZMQ.SNDMORE);
                            sock.send(rsp, ZMQ.PAIR);
                        } catch (Exception e) {
                            LOG.error(
                                    "ProtocolProcessor.callbackRun sock.send exception: "
                                            + e.getMessage());
                        }
                    }
                }
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        sock.close();
        if (LOG.isInfoEnabled()) {
            LOG.info("close eventRun Sockets...");
        }
    }

    private void txWaitRun() {
        while (!shutDown.get()) {
            ((HdlrZmq) this.handler).getTxWait();
        }
    }

    private void callbackRun(Context ctx) {
        Socket sock = ctx.socket(ZMQ.DEALER);
        sock.connect(AION_ZMQ_CB_TH);

        while (!shutDown.get()) {
            TxPendingStatus tps;
            try {
                tps = ((HdlrZmq) this.handler).getTxStatusQueue().take();
            } catch (InterruptedException e1) {
                // TODO Auto-generated catch block
                if (LOG.isErrorEnabled()) {
                    LOG.error("queue take exception - [{}]", e1.getMessage());
                }
                continue;
            }

            if (tps.isEmpty()) {
                continue;
            }

            byte[] rsp =
                    tps.toTxReturnCode() != 105
                            ? ((HdlrZmq) this.handler)
                                    .toRspMsg(
                                            tps.getMsgHash(), tps.toTxReturnCode(), tps.getError())
                            : ((HdlrZmq) this.handler)
                                    .toRspMsg(
                                            tps.getMsgHash(),
                                            tps.toTxReturnCode(),
                                            tps.getError(),
                                            tps.getTxResult());
            if (LOG.isTraceEnabled()) {
                LOG.trace(
                        "callbackRun send. socketID: [{}], msgHash: [{}], txReturnCode: [{}]/n rspMsg: [{}]",
                        Hex.toHexString(tps.getSocketId()),
                        Hex.toHexString(tps.getMsgHash()),
                        tps.toTxReturnCode(),
                        Hex.toHexString(rsp));
            }
            try {
                sock.send(tps.getSocketId(), ZMQ.SNDMORE);
                sock.send(rsp, ZMQ.PAIR);
            } catch (Exception e) {
                if (LOG.isErrorEnabled()) {
                    LOG.error(
                            "ProtocolProcessor.callbackRun sock.send exception: " + e.getMessage());
                }
            }
        }
        sock.close();
        if (LOG.isInfoEnabled()) {
            LOG.info("close callbackRun Sockets...");
        }
    }

    private void workerRun(ZMQ.Context ctx) {
        Socket sock = ctx.socket(ZMQ.DEALER);
        sock.connect(AION_ZMQ_WK_TH);

        while (!shutDown.get()) {
            try {
                byte[] socketId = sock.recv(0);
                if (LOG.isTraceEnabled()) {
                    LOG.trace(
                            "ProtocolProcessor.workerRun socketID: [{}]",
                            Hex.toHexString(socketId));
                }
                if (socketId != null && socketId.length == SOCKETID_LEN) {
                    byte[] req = sock.recv(0);
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("ProtocolProcessor.workerRun reqMsg: [{}]", Hex.toHexString(req));
                    }
                    byte[] rsp = ((HdlrZmq) this.handler).process(req, socketId);
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("ProtocolProcessor.workerRun rspMsg: [{}]", Hex.toHexString(rsp));
                    }

                    try {
                        sock.send(socketId, ZMQ.SNDMORE);
                        sock.send(rsp, ZMQ.PAIR);
                    } catch (Exception e) {
                        if (LOG.isErrorEnabled()) {
                            LOG.error(
                                    "ProtocolProcessor.workerRun sock.send exception: "
                                            + e.getMessage());
                        }
                    }
                } else {
                    if (LOG.isErrorEnabled()) {
                        LOG.error(
                                "ProtocolProcessor.workerRun incorrect socketID [{}]",
                                socketId == null ? "null" : Hex.toHexString(socketId));
                    }
                }
            } catch (Exception e) {
                if (LOG.isErrorEnabled()) {
                    LOG.error("ProtocolProcessor workerRun exception!! " + e.getMessage());
                }
            }
        }
        sock.close();
        if (LOG.isInfoEnabled()) {
            LOG.info("close workerRun Sockets...");
        }
    }

    private void hbRun(ZMQ.Context ctx) {
        Socket sock = ctx.socket(ZMQ.DEALER);
        sock.connect(AION_ZMQ_HB_TH);

        while (!shutDown.get()) {
            try {
                byte[] socketId = sock.recv(0);
                if (LOG.isTraceEnabled()) {
                    LOG.trace("ProtocolProcessor.hbRun socketID: [{}]", Hex.toHexString(socketId));
                }
                if (socketId != null && socketId.length == SOCKETID_LEN) {
                    byte[] req = sock.recv(0);
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("ProtocolProcessor.hbRun reqMsg: [{}]", Hex.toHexString(req));
                    }
                    byte[] rsp =
                            ApiUtil.toReturnHeader(
                                    JAVAAPI_VAR, Message.Retcode.r_heartbeatReturn_VALUE);
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("ProtocolProcessor.hbRun rspMsg: [{}]", Hex.toHexString(rsp));
                    }

                    try {
                        sock.send(socketId, ZMQ.SNDMORE);
                        sock.send(rsp, ZMQ.PAIR);
                    } catch (Exception e) {
                        if (LOG.isErrorEnabled()) {
                            LOG.error(
                                    "ProtocolProcessor.hbRun sock.send exception: "
                                            + e.getMessage());
                        }
                    }
                } else {
                    if (LOG.isErrorEnabled()) {
                        LOG.error(
                                "ProtocolProcessor.hbRun incorrect socketID [{}]",
                                socketId == null ? "null" : Hex.toHexString(socketId));
                    }
                }
            } catch (Exception e) {
                if (LOG.isErrorEnabled()) {
                    LOG.error("ProtocolProcessor hbRun exception!! " + e.getMessage());
                }
            }
        }
        sock.close();
        if (LOG.isInfoEnabled()) {
            LOG.info("close hbRun Sockets...");
        }
    }
}
