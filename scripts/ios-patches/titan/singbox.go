package titan

import (
	"context"
	"io"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
)

// startSingBox runs a sing-box client (a mixed inbound on 127.0.0.1 and the outbounds).
func startSingBox(config string) (io.Closer, error) {
	ctx := box.Context(context.Background(), include.InboundRegistry(), include.OutboundRegistry(),
		include.EndpointRegistry(), include.DNSTransportRegistry(), include.ServiceRegistry())
	opts, err := json.UnmarshalExtendedContext[option.Options](ctx, []byte(config))
	if err != nil {
		return nil, err
	}
	b, err := box.New(box.Options{Context: ctx, Options: opts})
	if err != nil {
		return nil, err
	}
	if err := b.Start(); err != nil {
		b.Close()
		return nil, err
	}
	return b, nil
}
