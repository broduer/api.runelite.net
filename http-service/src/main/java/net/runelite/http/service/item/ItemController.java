/*
 * Copyright (c) 2017-2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.http.service.item;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.util.concurrent.TimeUnit;
import net.runelite.http.api.item.ItemPrice;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/item")
public class ItemController
{
	private static class MemoizedPrices
	{
		final ItemPrice[] prices;
		final String hash;

		MemoizedPrices(ItemPrice[] prices)
		{
			this.prices = prices;

			Hasher hasher = Hashing.sha256().newHasher();
			for (ItemPrice itemPrice : prices)
			{
				hasher.putInt(itemPrice.hashCode());
			}
			HashCode code = hasher.hash();
			hash = code.toString();
		}
	}

	private final ItemService itemService;
	private final int priceCache;

	private MemoizedPrices memoizedPrices;

	@Autowired
	public ItemController(
		ItemService itemService,
		@Value("${runelite.price.cache}") int priceCache
	)
	{
		this.itemService = itemService;
		this.priceCache = priceCache;
	}

	@Scheduled(fixedDelayString = "${runelite.price.cache}", timeUnit = TimeUnit.MINUTES)
	private void updatePrices()
	{
		memoizedPrices = new MemoizedPrices(itemService.fetchPrices().stream()
			.map(priceEntry ->
			{
				ItemPrice itemPrice = new ItemPrice();
				itemPrice.setId(priceEntry.getItem());
				itemPrice.setName(priceEntry.getName());
				itemPrice.setPrice(priceEntry.getPrice());
				itemPrice.setWikiPrice(computeWikiPrice(priceEntry.getLow(), priceEntry.getHigh()));
				itemPrice.setWikiPriceFsw(computeWikiPrice(priceEntry.getFsw_low(), priceEntry.getFsw_high()));
				return itemPrice;
			})
			.toArray(ItemPrice[]::new));
	}

	private static int computeWikiPrice(int low, int high)
	{
		if (low > 0 && high > 0)
		{
			return (low + high) / 2;
		}
		else
		{
			return Math.max(low, high);
		}
	}

	@RequestMapping(value = { "/prices", "/prices.js" })
	public ResponseEntity<ItemPrice[]> prices()
	{
		if (memoizedPrices == null)
		{
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.cacheControl(CacheControl.noCache())
				.build();
		}

		return ResponseEntity.ok()
			.eTag(memoizedPrices.hash)
			.cacheControl(CacheControl.maxAge(priceCache, TimeUnit.MINUTES).cachePublic())
			.body(memoizedPrices.prices);
	}
}
