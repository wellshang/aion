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
package org.aion.zero.impl.tx;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.aion.mcf.types.AbstractTransaction;

@SuppressWarnings("rawtypes")
public class TxBroadcaster<TX extends AbstractTransaction, TXTASK extends AbstractTxTask> {

    private TxBroadcaster() {}

    private static class Holder {
        static final TxBroadcaster INSTANCE = new TxBroadcaster();
    }

    public static TxBroadcaster getInstance() {
        return Holder.INSTANCE;
    }

    private ExecutorService executor = Executors.newFixedThreadPool(1);

    @SuppressWarnings("unchecked")
    public Future<List<TX>> submitTransaction(TXTASK task) {
        return executor.submit(task);
    }
}
